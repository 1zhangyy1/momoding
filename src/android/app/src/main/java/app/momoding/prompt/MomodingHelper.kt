package app.momoding.prompt

/**
 * What a background job (a sub agent the chat delegated to) needs to know about working for
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
}
