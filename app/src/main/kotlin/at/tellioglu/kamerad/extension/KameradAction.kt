package at.tellioglu.kamerad.extension

/** Bonus actions declared in `extension_info.xml`, assignable to Karoo/AXS buttons. */
enum class KameradAction(val actionId: String) {
    TOGGLE_RECORDING("toggle-recording"),
    ;

    companion object {
        fun fromActionId(actionId: String): KameradAction? = entries.firstOrNull { it.actionId == actionId }
    }
}
