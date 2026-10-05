package ai.closepaw.agent.definition

import ai.closepaw.agent.AgentExecutionRole

internal val WORKSPACE_SHELL_PROMPT_SECTION =
    """
    ## Workspace Shell

    You have a termux_shell tool that provides a full Linux bash environment.
    Working directory: ~/closepaw/workspace/

    ### termux_shell
    Full Linux bash shell (via Termux). Supports pipe, redirect, all GNU coreutils.
    Available toolchain: python3, node/npm, git, gcc, cargo, go, etc. (depends on installed packages).
    Working directory: ~/closepaw/workspace/. Input and output files go in this directory.
    To share with other apps, cp to /sdcard/Download/.

    ### When to use which shell
    - If the user explicitly asks to run a command in Termux, always use termux_shell, including simple commands such as echo, pwd, whoami, or ssh.
    - termux_shell: commands that must run in the Termux/Linux environment, plus full-toolchain work (python/git/node, pipes, redirects, SSH, installed Termux packages).
    - shell: Android app-sandbox/device file checks only (ls/cat/stat) when the request is not for Termux/Linux execution.
    - Do not emulate Termux execution through shell, run-as, am, pm, UI typing, or Android broadcasts. If termux_shell is available, use it directly and return its actual adapter_id, exit_code, stdout, stderr, and timed_out result.

    ### When to use UI tools vs shell
    - UI tools (mobile_action, etc.): phone app interactions, screen navigation
    - termux_shell: files/commands/git/build/scripts
    - Combined: scrape data via browser UI → process with termux_shell. Email attachment → analyze with python.

    ### Guidelines
    - Do not use termux_shell to control Android UI or bypass app restrictions.
    - Input and output files go in ~/closepaw/workspace/.
    """.trimIndent()

private const val MAIN_WORKSPACE_SHELL_DIRECTIVE =
    "For workspace commands (termux_shell), execute directly instead of delegating."

internal fun workspaceShellPromptSectionFor(role: AgentExecutionRole): String {
    if (role != AgentExecutionRole.MAIN) return WORKSPACE_SHELL_PROMPT_SECTION

    return WORKSPACE_SHELL_PROMPT_SECTION + "\n\n" + MAIN_WORKSPACE_SHELL_DIRECTIVE
}
