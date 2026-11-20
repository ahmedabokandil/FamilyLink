package com.familylink.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.familylink.core.data.security.KeystorePassphraseProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        LocalIdentityEntity::class,
        PeerEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        MessageEntity::class,
        ReactionEntity::class,
        OutboxPacketEntity::class,
        RelayConfigEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class FamilyLinkDatabase : RoomDatabase() {
    abstract fun localIdentityDao(): LocalIdentityDao
    abstract fun peerDao(): PeerDao
    abstract fun groupDao(): GroupDao
    abstract fun messageDao(): MessageDao
    abstract fun reactionDao(): ReactionDao
    abstract fun outboxDao(): OutboxDao
    abstract fun relayConfigDao(): RelayConfigDao

    companion object {
        private const val DB_NAME = "familylink_encrypted.db"

        fun createEncrypted(context: Context): FamilyLinkDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = KeystorePassphraseProvider(context).getOrCreatePassphrase()
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(
                context.applicationContext,
                FamilyLinkDatabase::class.java,
                DB_NAME
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
