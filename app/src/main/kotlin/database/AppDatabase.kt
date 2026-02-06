package moe.kurenai.app.database

import java.nio.file.Path
import kotlin.io.path.absolutePathString

abstract class AppDatabase: RoomDatabase() {
}

fun getDatabaseBuilder(): RoomDatabase.Builder<AppDatabase> {
    val dbPath = Path.of("./data", "sqlite.db")
    return Room.databaseBuilder<AppDatabase>(
        name = dbPath.absolutePathString(),
    )
}