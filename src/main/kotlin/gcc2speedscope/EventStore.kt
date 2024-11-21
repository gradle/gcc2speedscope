package gcc2speedscope

import java.nio.file.Path

class EventStore(database: Path) : DataStore(database) {

    init {
        createStatement().execute(
            """
            CREATE TABLE frames (
                frame_id INT AUTO_INCREMENT PRIMARY KEY,
                frame TEXT NOT NULL,
                UNIQUE (frame)
            );

            CREATE TABLE profiles (
                profile_id INT AUTO_INCREMENT PRIMARY KEY,
                profile TEXT NOT NULL,
                UNIQUE (profile)
            );

            CREATE TABLE events (
                event_id INT AUTO_INCREMENT PRIMARY KEY,
                sn BIGINT,
                profile_id INT NOT NULL,
                type CHAR(1),
                frame_id INT NOT NULL,
                at BIGINT,
                UNIQUE (profile_id, sn)
-- The next constraint prevents the better UNIQUE index to be used for queries so it's not included
--                ,FOREIGN KEY (profile_id) REFERENCES profiles(profile_id)
-- The next constraint is excluded for performance
--                ,FOREIGN KEY (frame_id) REFERENCES frames(frame_id)
            );

            CREATE INDEX idx_frame ON frames(frame);

            CREATE INDEX idx_profile ON profiles(profile);

            """.trimIndent()
        )
    }

    private
    val selectFrame = prepare("SELECT frame_id FROM frames WHERE frame = ?")

    private
    val insertFrame = prepareInsert("INSERT INTO frames (frame) VALUES (?)")

    private
    val selectProfile = prepare("SELECT profile_id FROM profiles WHERE profile = ?")

    private
    val insertProfile = prepareInsert("INSERT INTO profiles (profile) VALUES (?)")

    private
    val insertEvent = prepare("INSERT INTO events (sn, profile_id, type, frame_id, at) VALUES (?, ?, ?, ?, ?)")

    private
    val selectProfileLastValue = prepare("SELECT at FROM events WHERE profile_id = ? ORDER BY sn DESC LIMIT 1")

    data class Profile(
        val id: Long,
        val name: String,
        val lastValue: Long
    )

    data class Event(
        val type: String,
        val frameIndex: Long,
        val at: Long
    )

    fun queryProfiles(): Sequence<Profile> = sequence {
        forEachIn("SELECT profile_id, profile FROM profiles") {
            val id = getLong(1)
            val profile = getString(2)
            val lastValue = lastValueOfProfile(id)
            yield(Profile(id, profile, lastValue))
        }
    }

    fun eventsOf(p: Profile): Sequence<Event> = sequence {
        forEachIn("SELECT type, frame_id, at FROM events WHERE profile_id = ${p.id} ORDER BY sn ASC") {
            val type = getString(1)
            val frameIndex = getLong(2)
            val at = getLong(3)
            yield(Event(type, frameIndex, at))
        }
    }

    /**
     * Stores the given event and returns its frame name if it's the first time it appears.
     */
    fun store(e: ParsedEvent): String? {
        try {
            val existingFrameIndex = frameIndexOf(e.frame)
            if (existingFrameIndex != null) {
                store(e, existingFrameIndex)
                return null
            }
            val newFrameIndex = insertFrame(e.frame)
            store(e, newFrameIndex)
            return e.frame
        } catch (x: Exception) {
            throw IllegalStateException("Failed to store event `$e`.", x)
        }
    }

    private
    fun store(e: ParsedEvent, frameIndex: Long) {
        val profileId = insertProfileIfAbsent(e.profile)
        insertEvent.run {
            setLong(1, e.sequenceNumber)
            setLong(2, profileId)
            setString(3, e.type)
            setLong(4, frameIndex)
            setLong(5, e.at)
            execute()
        }
    }

    private
    fun lastValueOfProfile(profileId: Long): Long =
        selectProfileLastValue.run {
            setLong(1, profileId)
            executeQuery().run {
                require(next())
                getLong(1)
            }
        }

    private
    fun insertProfileIfAbsent(profile: String) =
        selectProfile.queryLong(profile)
            ?: insertProfile.insertAndGetGeneratedKey(profile)

    private
    fun frameIndexOf(frameName: String): Long? =
        selectFrame.queryLong(frameName)

    private
    fun insertFrame(frameName: String): Long =
        insertFrame.insertAndGetGeneratedKey(frameName)

}