package pt.isel.pc.problemsets.set3.base

/**
 * Sealed interface to represent all possible request lines sent by the client.
 */
sealed interface ClientRequest {

    // Message to be sent to the room where the client currently is
    data class Message(val value: String) : ClientRequest

    // Command asking to enter a room
    data class EnterRoomCommand(val name: String) : ClientRequest

    // Command asking to leave the current room
    object LeaveRoomCommand : ClientRequest {
        override fun toString(): String = "LeaveRoomCommand"
    }

    // Command asking to exit the server
    object ExitCommand : ClientRequest {
        override fun toString(): String = "ExitCommand"
    }

    // Invalid request
    data class InvalidRequest(val reason: String) : ClientRequest

    companion object {
        fun parse(line: String): ClientRequest {
            if (!line.startsWith("/")) {
                return Message(line)
            }
            val parts = line.split(" ")
            return when (parts[0]) {
                "/enter" -> parseEnterRoom(parts)
                "/leave" -> parseLeaveRoom(parts)
                "/exit" -> parseExit(parts)
                else -> InvalidRequest("unknown command")
            }
        }

        private fun parseEnterRoom(parts: List<String>): ClientRequest =
            if (parts.size != 2) {
                InvalidRequest("/enter command requires exactly one argument")
            } else {
                EnterRoomCommand(parts[1])
            }

        private fun parseLeaveRoom(parts: List<String>): ClientRequest =
            if (parts.size != 1) {
                InvalidRequest("/leave command does not have arguments")
            } else {
                LeaveRoomCommand
            }

        private fun parseExit(parts: List<String>): ClientRequest =
            if (parts.size != 1) {
                InvalidRequest("/exit command does not have arguments")
            } else {
                ExitCommand
            }
    }
}