package pt.isel.pc.problemsets.set3.base

/**
 * Messages returned to the client or written into the standard output.
 */
object Messages {
    val APP_COMMANDS = listOf(
        "Available commands:",
        "- /shutdown <timeout>",
        "- /exit"
    ).joinToString("\n")
    val CLIENT_COMMANDS = listOf(
        "Available commands:",
        "- /enter <room>",
        "- /leave",
        "- /exit"
    ).joinToString(System.lineSeparator())
    const val SERVER_IS_BOUND = "Server is ready for business."
    const val SERVER_IS_ENDING = "Server is ending, bye."
    const val SERVER_ACCEPTED_CLIENT = "Server accepted client."
    const val CLIENT_WELCOME = "Welcome to the chat server."
    const val ERR_NOT_IN_A_ROOM = "Error: cannot send a message while not in a room."
    const val ERR_INVALID_LINE = "Error: invalid line."
    const val BYE = "Bye."
    const val SEE_APP_COMMANDS = "use /commands to see the available commands"
    const val SHUTDOWN_COMMAND_TIMEOUT_NOT_SPECIFIED = "timeout not specified for /shutdown command"
    const val SHUTDOWN_COMMAND_INVALID_TIMEOUT = "Invalid timeout for /shutdown command"
    const val EXIT_COMMAND_DOES_NOT_HAVE_ARGUMENTS = "/exit command does not have arguments"
    const val AVAILABLE_COMMANDS_COMMAND_DOES_NOT_HAVE_ARGUMENTS = "/commands command does not have arguments"
    fun enteredRoom(name: String) = "Info: entered room $name"
    fun messageFromClient(client: String, msg: String) = "'$client' says: $msg"
    fun unknownAppCommand(gibberish: String): String = "Unknown command: $gibberish"
}