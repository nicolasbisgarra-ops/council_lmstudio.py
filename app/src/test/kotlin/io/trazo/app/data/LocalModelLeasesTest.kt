package io.trazo.app.data

import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalModelLeasesTest {
    private fun model(): File = Files.createTempFile("lease-", ".gguf").toFile().apply { deleteOnExit() }
    private fun denied(body: () -> Unit) {
        var didFail = false
        try { body() } catch (e: IllegalStateException) { didFail = true }
        check(didFail) { "Expected operation to be blocked" }
    }
    @Test fun deleteDeniedWhileActive() {
        val f=model();val guard=LocalModelLeases();val lease=guard.acquire(f)
        denied { guard.whileUnused(f) { f.delete() } }
        check(f.exists());lease.close()
    }
    @Test fun deleteSucceedsAfterRelease() {
        val f=model();val guard=LocalModelLeases();val lease=guard.acquire(f)
        lease.close();guard.whileUnused(f) { f.delete() };check(!f.exists())
    }
    @Test fun duplicateLeaseReleaseIsSafe() {
        val f=model();val guard=LocalModelLeases();val lease=guard.acquire(f)
        lease.close();lease.close();check(guard.activeLeases(f)==0)
    }
    @Test fun twoConsumersMustBothRelease() {
        val f=model();val guard=LocalModelLeases();val first=guard.acquire(f);val second=guard.acquire(f)
        check(guard.activeLeases(f)==2);first.close();denied { guard.whileUnused(f){ f.delete() } };second.close()
        check(guard.activeLeases(f)==0)
    }
    @Test fun movingSymlinkLikeAliasSameCanonicalFileSharesLease() {
        val f=model();val guard=LocalModelLeases();val first=guard.acquire(f)
        val alias=File(f.parentFile,"sub/../"+f.name)
        denied { guard.whileUnused(alias){f.delete()} };first.close()
    }
    @Test fun projectorRemovalDeniedIfAnyAttachedModelIsInUse() {
        val a=model();val b=model();val guard=LocalModelLeases();val lease=guard.acquire(a)
        denied { guard.whileUnused(listOf(b,a)){ a.delete();b.delete() } }
        check(a.exists()&&b.exists());lease.close()
    }
    @Test fun unrelatedModelCanBeDeletedWhileAnotherUsed() {
        val a=model();val b=model();val guard=LocalModelLeases();val lease=guard.acquire(a)
        guard.whileUnused(b){b.delete()};check(!b.exists()&&a.exists());lease.close()
    }
}
