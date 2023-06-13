package pt.isel.pc.problemsets.set3.solution

/**
 * Commands that can be sent to the application.
 */
sealed interface AppCommand {

    // Command asking to shut down the application within a given timeout
    data class ShutdownCommand(val timeout: Long) : AppCommand

    // Command asking to exit the application
    object ExitCommand : AppCommand {
        override fun toString(): String = "ExitCommand"
    }

    // Command asking for the available commands
    object AvailableCommands : AppCommand {
        override fun toString(): String = "AvailableCommands"
    }

    // Command that is not recognized
    data class UnknownCommand(val gibberish: String) : AppCommand

    companion object {
        fun parse(line: String): AppCommand {
            if (!line.startsWith("/")) {
                return UnknownCommand("use /commands to see the available commands")
            }
            val parts = line.split(" ")
            return when (parts.first()) {
                "/shutdown" -> parseShutdown(parts)
                "/exit" -> parseExit(parts)
                "/commands" -> parseAvalaibleCommands(parts)
                else -> UnknownCommand(line)
            }
        }

        private fun parseShutdown(parts: List<String>): AppCommand {
            if (parts.size != 2) {
                return UnknownCommand("timeout not specified for /shutdown command")
            }
            val timeout = parts[1].toLongOrNull()
            if (timeout == null || timeout <= 0) {
                return UnknownCommand("Invalid timeout for /shutdown command")
            }
            return ShutdownCommand(timeout)
        }

        private fun parseExit(parts: List<String>): AppCommand =
            if (parts.size != 1) {
                UnknownCommand("/exit command does not have arguments")
            } else {
                ExitCommand
            }

        private fun parseAvalaibleCommands(parts: List<String>): AppCommand =
            if (parts.size != 1) {
                UnknownCommand("/commands command does not have arguments")
            } else {
                AvailableCommands
            }
    }
}