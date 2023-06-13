package pt.isel.pc.problemsets.set3.base

/**
 * Messages returned to the client or written into the standard output.
 */
object Messages {
    val lineTerminator: String = System.lineSeparator()
    const val SERVER_IS_BOUND = "Server is ready for business."
    const val SERVER_IS_ENDING = "Server is ending, bye."
    const val SERVER_ACCEPTED_CLIENT = "Server accepted client."
    val CLIENT_WELCOME = listOf("Welcome to the chat server", "Avaliable commands:", "- /enter <room>", "- /leave", "- /exit.").addLineTerminator()
    val ERR_NOT_IN_A_ROOM = listOf("Error: cannot send a message while not in a room.").addLineTerminator()
    val ERR_INVALID_LINE = listOf("Error: invalid line received.").addLineTerminator()
    const val BYE = "Bye."
    fun enteredRoom(name: String) = listOf("Info: entered room $name.").addLineTerminator()
    fun messageFromClient(client: String, msg: String) = listOf("'$client' says: $msg").addLineTerminator()
    private fun List<String>.addLineTerminator(): String {
        return joinToString(separator = lineTerminator, postfix = lineTerminator)
    }
}