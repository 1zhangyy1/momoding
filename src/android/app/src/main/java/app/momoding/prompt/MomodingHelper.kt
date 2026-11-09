package app.momoding.prompt

/**
 * What a helper (a hand the main chat gave a task) needs to know about working for
 * Momoding, added to the upstream helper preamble (调研/M19). Principles, not per-site rules:
 * the slow, costly runs came from pushing through walled sites and gathering more than the
 * person needs, and a child must never act on the person's accounts or phone on its own.
 */
object MomodingHelper {
    /**
     * Hard budget for a background job. The upstream 200 rounds / up to 60 minutes suit coding;
     * on the phone the cost came from one job pushing through walled flight sites for 96 rounds
     * (13.5 min, 4.2M cached tokens). At the cap the job is asked to write its answer from what
     * it has, so a capped job still reports back.
     */
    const val MAX_TURNS = 40
    const val MAX_MINUTES = 8

    const val SECTION = """
Working in the background for a person's phone assistant:
- Stop when there is enough to decide on: a clear answer from one or two reliable sources beats an exhaustive survey. Time and tokens are the person's money.
- A site that walls you off (bot check, login, captcha, "open in the app") is a blocker, not a puzzle: do not retry it or hop through many mirrors. Try at most one other source, then report what you could not get.
- Never send messages, pay, order, book, sign in with the person's accounts, change their phone (calendar, alarms, contacts, settings, files) or operate the phone's screen. If the job needs any of that, stop and say exactly what is needed in your final answer; the assistant will ask the person.
- Your final answer is the result itself, compact: the facts with their numbers, where they came from, and what is uncertain or missing. No narration of the steps you took. Write it in the language of the task.
"""

    /** How many rounds before the cap the helper hears how many it has left. */
    const val TURN_WARNING_LEAD = 3

    /** A helper's preamble in place of the identity section: where it runs and what its answer is for. */
    fun identity(cfg: app.momoding.task.HelperConfig, browserEnabled: Boolean): String =
        "You are running as a helper inside an app called Momoding, on an Android device with a fully functional Linux shell (Alpine Linux, aarch64). This is the calling environment, not your identity — keep your own model identity unchanged.\n" +
            "The assistant handed you ONE focused task: \"${cfg.title}\". You cannot see its conversation and it cannot see yours; everything you need is in the task text. Work the task to completion using your tools, then finish with a single clear final answer — that final message is the ONLY thing the assistant receives. So it must contain the complete deliverable itself, never a summary of what you did, a pointer to a file you wrote, or \"see above\". If you produced a file, paste its full content into the final message as well. Do not ask questions; make reasonable assumptions and state them. Do not create scheduled tasks or hand work on.\n" +
            (if (browserEnabled) "Browser: the browser is shared with the assistant and with other helpers. You may only use your own tabs (up to ${com.openminis.app.browser.BrowserTabPool.AGENT_TAB_QUOTA}); list_tabs shows just yours. To open a page, navigate (a tab of yours is picked or created) or use new_tab; never guess tab ids you did not receive, and reuse your tabs by navigating them instead of opening more.\n" else "") +
            SECTION.trimStart() +
            "You have at most ${cfg.maxTurns} tool rounds; on the last one tools are withdrawn and you are asked to write the deliverable from what you have, so start writing before you run out.\n\n"

    /** Said when tools are withdrawn: the rounds or the time are used up. */
    fun wrapUp(outOfTurns: Boolean): String =
        "[${if (outOfTurns) "You have used all of your tool rounds." else "Your time budget is up."} Tools are no longer available for this turn.]\n" +
            "Write your final answer NOW from what you already have. It must contain the complete deliverable itself — the full report, document, list or answer the task asked for — not a description of what you did, not a pointer to a file, not a request for more time. Mark anything you could not verify as unverified."

    /** Said [TURN_WARNING_LEAD] rounds before the cap, while tools still work. */
    fun turnWarning(remaining: Int): String =
        "[Budget check: $remaining tool ${if (remaining == 1) "round" else "rounds"} left before tools are withdrawn.] " +
            "Stop investigating now and spend what is left on finishing: if the task asked you to write a file or produce an artifact, do it in the next round, then write your final answer. " +
            "Remember the final message must contain the complete deliverable itself, not a pointer to it."
}
