package pt.isel.pc.problemsets.set3.base

import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a container of rooms, organized by room name.
 */
class RoomContainer {

    private val roomMap = ConcurrentHashMap<String, Room>()

    /**
     * Gets a room by name, creating it if it does not exist yet.
     */
    fun getByName(name: String): Room = roomMap.computeIfAbsent(name) { Room(name) }
}