#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {
constexpr const char * kTag = "TrazoLlama";
constexpr int32_t kDecodeBatch = 256;

struct Engine {
    std::mutex mutex;
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    mtmd_context * multimodal = nullptr;
    int32_t context_tokens = 0;
    std::atomic<bool> cancelled{false};
};

void log_error(const std::string & message) {
    __android_log_write(ANDROID_LOG_ERROR, kTag, message.c_str());
}

void throw_java(JNIEnv * env, const char * type, const std::string & message) {
    jclass cls = env->FindClass(type);
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
        env->DeleteLocalRef(cls);
    }
}

class UtfChars {
public:
    UtfChars(JNIEnv * env, jstring value) : env_(env), value_(value) {
        if (value != nullptr) chars_ = env_->GetStringUTFChars(value, nullptr);
    }
    ~UtfChars() {
        if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_);
    }
    const char * get() const { return chars_; }
private:
    JNIEnv * env_;
    jstring value_;
    const char * chars_ = nullptr;
};

void unload_locked(Engine * engine) {
    if (engine->multimodal != nullptr) {
        mtmd_free(engine->multimodal);
        engine->multimodal = nullptr;
    }
    if (engine->context != nullptr) {
        llama_free(engine->context);
        engine->context = nullptr;
    }
    if (engine->model != nullptr) {
        llama_model_free(engine->model);
        engine->model = nullptr;
    }
    engine->context_tokens = 0;
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(128);
    int32_t n = llama_token_to_piece(vocab, token, buffer.data(),
                                     static_cast<int32_t>(buffer.size()), 0, false);
    if (n < 0) {
        buffer.resize(static_cast<size_t>(-n));
        n = llama_token_to_piece(vocab, token, buffer.data(),
                                 static_cast<int32_t>(buffer.size()), 0, false);
    }
    if (n <= 0) return {};
    return std::string(buffer.data(), static_cast<size_t>(n));
}

bool invoke_partial(JNIEnv * env, jobject callback, const std::string & piece) {
    if (callback == nullptr || piece.empty()) return true;
    jclass cls = env->GetObjectClass(callback);
    if (cls == nullptr) return false;
    jmethodID invoke = env->GetMethodID(cls, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
    env->DeleteLocalRef(cls);
    if (invoke == nullptr) return false;
    jstring value = env->NewStringUTF(piece.c_str());
    if (value == nullptr) return false;
    jobject ignored = env->CallObjectMethod(callback, invoke, value);
    if (ignored != nullptr) env->DeleteLocalRef(ignored);
    env->DeleteLocalRef(value);
    return !env->ExceptionCheck();
}

std::string format_chat(Engine * engine, const std::string & system,
                        const std::string & prompt, bool with_image) {
    std::string user = prompt;
    if (with_image) {
        user.insert(0, std::string(mtmd_default_marker()) + "\n");
    }

    const char * chat_template = llama_model_chat_template(engine->model, nullptr);
    if (chat_template == nullptr || chat_template[0] == '\0') {
        throw std::runtime_error("El GGUF no incluye una plantilla de chat compatible.");
    }

    llama_chat_message messages[2] = {
        { "system", system.c_str() },
        { "user", user.c_str() },
    };
    int32_t needed = llama_chat_apply_template(chat_template, messages, 2, true, nullptr, 0);
    if (needed <= 0) {
        throw std::runtime_error("llama.cpp no pudo aplicar la plantilla de chat del GGUF.");
    }
    std::vector<char> output(static_cast<size_t>(needed) + 1);
    int32_t written = llama_chat_apply_template(
        chat_template, messages, 2, true, output.data(), static_cast<int32_t>(output.size()));
    if (written < 0 || written > needed) {
        throw std::runtime_error("llama.cpp devolvió una plantilla de chat inválida.");
    }
    return std::string(output.data(), static_cast<size_t>(written));
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & prompt) {
    int32_t n = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                               nullptr, 0, false, true);
    if (n >= 0) {
        if (n == 0) throw std::runtime_error("El pedido no produjo tokens.");
    } else {
        n = -n;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(n));
    int32_t actual = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                    tokens.data(), n, false, true);
    if (actual < 0) throw std::runtime_error("No se pudo tokenizar el pedido.");
    tokens.resize(static_cast<size_t>(actual));
    return tokens;
}

bool decode_prompt(Engine * engine, const std::vector<llama_token> & tokens) {
    for (size_t offset = 0; offset < tokens.size();) {
        if (engine->cancelled.load(std::memory_order_relaxed)) {
            return false;
        }
        const int32_t count = static_cast<int32_t>(
            std::min(static_cast<size_t>(kDecodeBatch), tokens.size() - offset));
        llama_batch batch = llama_batch_get_one(
            const_cast<llama_token *>(tokens.data() + offset), count);
        if (llama_decode(engine->context, batch) != 0) {
            throw std::runtime_error("llama_decode falló al procesar el pedido.");
        }
        offset += static_cast<size_t>(count);
    }
    return !engine->cancelled.load(std::memory_order_relaxed);
}

std::string generate_locked(JNIEnv * env, Engine * engine, const std::string & system,
                            const std::string & prompt, const std::vector<uint8_t> & image,
                            int32_t max_tokens, jobject callback) {
    const bool with_image = !image.empty();
    if (with_image && engine->multimodal == nullptr) {
        throw std::runtime_error("Falta el proyector mmproj de llama.cpp para procesar imágenes.");
    }
    if (with_image && !mtmd_support_vision(engine->multimodal)) {
        throw std::runtime_error("El archivo mmproj no declara compatibilidad con imágenes.");
    }

    const std::string formatted = format_chat(engine, system, prompt, with_image);
    const llama_vocab * vocab = llama_model_get_vocab(engine->model);
    if (vocab == nullptr) throw std::runtime_error("El GGUF no expone vocabulario.");

    llama_memory_clear(llama_get_memory(engine->context), true);
    llama_pos prompt_positions = 0;
    mtmd_input_chunks * chunks = nullptr;
    mtmd_helper_bitmap_wrapper image_wrapper = {};
    auto cleanup = [&]() {
        if (chunks != nullptr) {
            mtmd_input_chunks_free(chunks);
            chunks = nullptr;
        }
        if (image_wrapper.bitmap != nullptr) {
            mtmd_bitmap_free(image_wrapper.bitmap);
            image_wrapper.bitmap = nullptr;
        }
        if (image_wrapper.video_ctx != nullptr) {
            mtmd_helper_video_free(image_wrapper.video_ctx);
            image_wrapper.video_ctx = nullptr;
        }
    };
    try {
        if (with_image) {
            mtmd_helper_init_opt options = mtmd_helper_init_opt_default();
            image_wrapper = mtmd_helper_bitmap_init_from_buf(
                engine->multimodal, image.data(), image.size(), false, options);
            if (image_wrapper.bitmap == nullptr) {
                throw std::runtime_error("llama.cpp no pudo decodificar la imagen PNG/JPEG.");
            }
            chunks = mtmd_input_chunks_init();
            if (chunks == nullptr) throw std::runtime_error("No se pudo crear el lote multimodal.");
            mtmd_input_text input = {
                formatted.c_str(), formatted.size(), false, true
            };
            const mtmd_bitmap * bitmaps[] = { image_wrapper.bitmap };
            if (mtmd_tokenize(engine->multimodal, chunks, &input, bitmaps, 1) != 0) {
                throw std::runtime_error("llama.cpp no pudo tokenizar la imagen y el pedido.");
            }
            const size_t prompt_tokens = mtmd_helper_get_n_tokens(chunks);
            if (prompt_tokens == 0 || prompt_tokens + static_cast<size_t>(max_tokens) >
                                      static_cast<size_t>(engine->context_tokens)) {
                throw std::runtime_error("La imagen y el pedido superan el contexto disponible.");
            }
            if (mtmd_helper_eval_chunks(engine->multimodal, engine->context, chunks, 0, 0,
                                       kDecodeBatch, true, &prompt_positions) != 0) {
                throw std::runtime_error("llama.cpp no pudo procesar la imagen.");
            }
            if (engine->cancelled.load(std::memory_order_relaxed)) {
                cleanup();
                return {};
            }
        } else {
            std::vector<llama_token> tokens = tokenize(vocab, formatted);
            if (tokens.size() + static_cast<size_t>(max_tokens) >
                static_cast<size_t>(engine->context_tokens)) {
                throw std::runtime_error("El pedido supera el contexto configurado para este modelo.");
            }
            if (!decode_prompt(engine, tokens)) return {};
        }

        llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
        llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
        if (sampler == nullptr) throw std::runtime_error("No se pudo crear el muestreador llama.cpp.");
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.2f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        std::string result;
        result.reserve(static_cast<size_t>(max_tokens) * 4);
        for (int32_t i = 0; i < max_tokens; ++i) {
            if (engine->cancelled.load(std::memory_order_relaxed)) break;
            const llama_token token = llama_sampler_sample(sampler, engine->context, -1);
            llama_sampler_accept(sampler, token);
            if (llama_vocab_is_eog(vocab, token)) break;
            const std::string piece = token_piece(vocab, token);
            result += piece;
            if (!invoke_partial(env, callback, piece)) {
                engine->cancelled.store(true, std::memory_order_relaxed);
                llama_sampler_free(sampler);
                cleanup();
                return result;
            }
            llama_token next = token;
            llama_batch batch = llama_batch_get_one(&next, 1);
            if (llama_decode(engine->context, batch) != 0) {
                llama_sampler_free(sampler);
                throw std::runtime_error("llama_decode falló durante la generación.");
            }
        }
        llama_sampler_free(sampler);
        cleanup();
        return result;
    } catch (...) {
        cleanup();
        throw;
    }
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_trazo_app_ai_LlamaCppInference_nativeCreate(JNIEnv *, jobject) {
    static std::once_flag backend_once;
    std::call_once(backend_once, [] { llama_backend_init(); });
    return reinterpret_cast<jlong>(new Engine());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_trazo_app_ai_LlamaCppInference_nativeLoad(JNIEnv * env, jobject, jlong handle,
                                                   jstring model_path, jstring projector_path,
                                                   jint context_tokens) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr) return env->NewStringUTF("El motor llama.cpp no está inicializado.");
    UtfChars model_chars(env, model_path);
    UtfChars projector_chars(env, projector_path);
    if (model_chars.get() == nullptr) return env->NewStringUTF("La ruta del GGUF está vacía.");

    std::lock_guard<std::mutex> guard(engine->mutex);
    unload_locked(engine);
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU-only path; do not claim GPU acceleration.
    engine->model = llama_model_load_from_file(model_chars.get(), model_params);
    if (engine->model == nullptr) {
        return env->NewStringUTF("No se pudo abrir el archivo GGUF con llama.cpp.");
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(context_tokens);
    context_params.n_batch = kDecodeBatch;
    context_params.n_ubatch = kDecodeBatch;
    const int processors = static_cast<int>(std::thread::hardware_concurrency());
    context_params.n_threads = std::max(2, std::min(4, processors > 2 ? processors - 1 : 2));
    context_params.n_threads_batch = context_params.n_threads;
    context_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    engine->context = llama_init_from_model(engine->model, context_params);
    if (engine->context == nullptr) {
        unload_locked(engine);
        return env->NewStringUTF("No se pudo asignar memoria para el contexto del GGUF.");
    }
    engine->context_tokens = static_cast<int32_t>(llama_n_ctx(engine->context));

    if (projector_chars.get() != nullptr && projector_chars.get()[0] != '\0') {
        mtmd_context_params multimodal_params = mtmd_context_params_default();
        multimodal_params.use_gpu = false;
        multimodal_params.print_timings = false;
        multimodal_params.n_threads = context_params.n_threads;
        engine->multimodal = mtmd_init_from_file(projector_chars.get(), engine->model, multimodal_params);
        if (engine->multimodal == nullptr) {
            unload_locked(engine);
            return env->NewStringUTF("No se pudo abrir el proyector mmproj para este GGUF.");
        }
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_io_trazo_app_ai_LlamaCppInference_nativeUnload(JNIEnv *, jobject, jlong handle) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr) return;
    std::lock_guard<std::mutex> guard(engine->mutex);
    unload_locked(engine);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_trazo_app_ai_LlamaCppInference_nativeGenerate(JNIEnv * env, jobject, jlong handle,
                                                       jstring system, jstring prompt,
                                                       jbyteArray image_bytes, jint max_tokens,
                                                       jobject callback) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "El motor llama.cpp no está inicializado.");
        return nullptr;
    }
    UtfChars system_chars(env, system);
    UtfChars prompt_chars(env, prompt);
    if (prompt_chars.get() == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "El pedido está vacío.");
        return nullptr;
    }
    std::vector<uint8_t> image;
    if (image_bytes != nullptr) {
        const jsize size = env->GetArrayLength(image_bytes);
        if (size > 0) {
            image.resize(static_cast<size_t>(size));
            env->GetByteArrayRegion(image_bytes, 0, size, reinterpret_cast<jbyte *>(image.data()));
            if (env->ExceptionCheck()) return nullptr;
        }
    }

    std::lock_guard<std::mutex> guard(engine->mutex);
    if (engine->model == nullptr || engine->context == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "Primero cargue un modelo GGUF válido.");
        return nullptr;
    }
    try {
        const std::string output = generate_locked(
            env, engine, system_chars.get() == nullptr ? "" : system_chars.get(),
            prompt_chars.get(), image, max_tokens, callback);
        return env->NewStringUTF(output.c_str());
    } catch (const std::exception & error) {
        log_error(error.what());
        throw_java(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Fallo nativo desconocido en llama.cpp.");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_trazo_app_ai_LlamaCppInference_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr) return;
    {
        std::lock_guard<std::mutex> guard(engine->mutex);
        unload_locked(engine);
    }
    delete engine;
}

extern "C" JNIEXPORT void JNICALL
Java_io_trazo_app_ai_LlamaCppInference_nativeResetCancel(JNIEnv *, jobject, jlong handle) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (engine != nullptr) engine->cancelled.store(false, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_io_trazo_app_ai_LlamaCppInference_nativeCancel(JNIEnv *, jobject, jlong handle) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (engine != nullptr) engine->cancelled.store(true, std::memory_order_relaxed);
}
