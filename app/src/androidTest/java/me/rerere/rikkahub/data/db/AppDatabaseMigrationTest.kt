package me.rerere.rikkahub.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation coverage for the Room chain around the 2.5.1 port.
 *
 * - `23 -> 31` on an empty database: the fork's migration list used to jump from `22 -> 23`
 *   straight to `24 -> 25`, which left every install still sitting on v23 without a path.
 * - `30 -> 31` on a database that already holds a workspace row: the upstream column
 *   `shell_compatibility_mode` must appear with the documented default of 0 and the existing
 *   row must survive.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    // Two-argument form on purpose: AutoMigrationSpec lives in room-common, which is not on the
    // androidTest compile classpath, so the specs/openFactory defaults must come from the library.
    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun emptyDatabaseMigrates23To31() {
        helper.createDatabase(DB_FROM_23, 23).close()

        val db = helper.runMigrationsAndValidate(DB_FROM_23, 31, true)

        // agent_runs is created by the 23 -> 24 step that master had dropped.
        assertEquals(1, count(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='agent_runs'"))
        // workspaces arrives at v26; at v31 it must carry shell_compatibility_mode.
        assertEquals(1, count(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='workspaces'"))
        assertEquals(0, count(db, "SELECT COUNT(*) FROM workspaces"))
        db.close()
    }

    @Test
    fun existingWorkspaceRowSurvives30To31() {
        helper.createDatabase(DB_FROM_30, 30).apply {
            execSQL(
                "INSERT INTO workspaces " +
                    "(id, name, root, shell_status, created_at, updated_at, tool_approvals) " +
                    "VALUES ('ws-1', 'keep-me', '/workspace', 'idle', 1, 2, '{}')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB_FROM_30, 31, true)

        db.query("SELECT name, shell_compatibility_mode FROM workspaces").use { c ->
            assertTrue("the workspace row must survive the 30 -> 31 migration", c.moveToFirst())
            assertEquals(1, c.count)
            assertEquals("keep-me", c.getString(0))
            assertEquals(0, c.getInt(1))
        }
        db.close()
    }

    private fun count(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }

    private companion object {
        const val DB_FROM_23 = "migration-23-31"
        const val DB_FROM_30 = "migration-30-31"
    }
}
