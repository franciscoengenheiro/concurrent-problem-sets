package pt.isel.pc.problemsets.set3.base

/**
 * Messages returned to the client or written into the standard output.
 */
object Messages {
    const val SERVER_IS_BOUND = "Server is ready for business."
    const val SERVER_IS_ENDING = "Server is ending, bye."
    const val SERVER_ACCEPTED_CLIENT = "Server accepted client."
    const val CLIENT_WELCOME = "Welcome to the chat server."
    const val ERR_NOT_IN_A_ROOM = "Error: cannot send a message while not in a room."
    const val ERR_INVALID_LINE = "Error: invalid line."
    const val BYE = "Bye."

    fun enteredRoom(name: String) = "Info: entered room $name"
    fun messageFromClient(client: String, msg: String) = "'$client' says: $msg"
}