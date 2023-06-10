package pt.isel.pc.problemsets.set3.base

/**
 * Messages returned to the client or written into the standard output.
 */
object Messages {
    val lineTerminator = System.lineSeparator()
    private val commandList = listOf(
        "Available commands:",
        "- /enter <room>",
        "- /leave",
        "- /exit."
    )
    val AVAILABLE_COMMANDS = commandList.addLineTerminator()
    const val SERVER_IS_BOUND = "Server is ready for business."
    const val SERVER_IS_ENDING = "Server is ending, bye."
    const val SERVER_ACCEPTED_CLIENT = "Server accepted client."
    val CLIENT_WELCOME = listOf("Welcome to the chat server.").addLineTerminator()
    val ERR_NOT_IN_A_ROOM = "Error: cannot send a message while not in a room."
    val ERR_INVALID_LINE = "Error: invalid line received."
    val BYE = listOf("Bye.").addLineTerminator()
    fun enteredRoom(name: String) = "Info: entered room $name."
    fun messageFromClient(client: String, msg: String) = "'$client' says: $msg"
    private fun List<String>.addLineTerminator(): String {
        return joinToString(separator = lineTerminator, postfix = lineTerminator)
    }
}