package com.schoolapp.core.logic

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocalFirstChangeTest {

    private class PermissionDenied : Exception("PERMISSION_DENIED: Missing or insufficient permissions.")

    @Test
    fun `an accepted change stays and nothing is undone`() = runTest {
        val steps = mutableListOf<String>()

        LocalFirstChange.run(
            apply = { steps += "apply" },
            upload = { steps += "upload" },
            undo = { steps += "undo" },
        )

        assertThat(steps).containsExactly("apply", "upload").inOrder()
    }

    @Test
    fun `a refused change is undone and reported, never returned as success`() = runTest {
        val steps = mutableListOf<String>()

        val error = runCatching {
            LocalFirstChange.run(
                apply = { steps += "apply" },
                upload = { steps += "upload"; throw PermissionDenied() },
                undo = { steps += "undo" },
            )
        }.exceptionOrNull()

        assertThat(steps).containsExactly("apply", "upload", "undo").inOrder()
        assertThat(error).isInstanceOf(ChangeRejectedException::class.java)
        assertThat(error).hasCauseThat().isInstanceOf(PermissionDenied::class.java)
        assertThat(error).hasMessageThat().contains("refused")
        assertThat(error).hasMessageThat().contains("PERMISSION_DENIED")
    }

    @Test
    fun `a refused delete puts the student and their scores back`() = runTest {
        // A stand-in for Room: deleting a student also deletes their cached scores.
        val students = mutableMapOf("s1" to "Aisha")
        val scores = mutableMapOf("math_s1" to 91.0, "sci_s1" to 88.0)
        val savedStudents = students.toMap()
        val savedScores = scores.toMap()

        val error = runCatching {
            LocalFirstChange.run(
                apply = { students.remove("s1"); scores.clear() },
                upload = { throw PermissionDenied() },
                undo = { students.putAll(savedStudents); scores.putAll(savedScores) },
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(ChangeRejectedException::class.java)
        assertThat(students).containsExactly("s1", "Aisha")
        assertThat(scores).containsExactly("math_s1", 91.0, "sci_s1", 88.0)
    }

    @Test
    fun `a cancelled wait is not a refusal and keeps the local change`() = runTest {
        val steps = mutableListOf<String>()

        val error = runCatching {
            LocalFirstChange.run(
                apply = { steps += "apply" },
                upload = { throw CancellationException("screen closed") },
                undo = { steps += "undo" },
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
        assertThat(error).isNotInstanceOf(ChangeRejectedException::class.java)
        assertThat(steps).containsExactly("apply")
    }

    @Test
    fun `a failing undo is attached to the refusal`() = runTest {
        val error = runCatching {
            LocalFirstChange.run(
                apply = {},
                upload = { throw PermissionDenied() },
                undo = { error("disk full") },
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(ChangeRejectedException::class.java)
        assertThat(error!!.cause!!.suppressed.map { it.message }).containsExactly("disk full")
    }

    @Test
    fun `the refusal message works without a cause message`() {
        assertThat(ChangeRejectedException(Exception()).message)
            .isEqualTo("The school server refused this change, so it was undone on this device.")
    }
}
