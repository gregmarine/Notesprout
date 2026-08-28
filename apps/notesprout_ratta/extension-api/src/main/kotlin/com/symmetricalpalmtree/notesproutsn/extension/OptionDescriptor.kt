package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * One option an exporter offers, rendered by the **host's own widgets** so every exporter's panel
 * is native e-ink chrome. The constructor `require`s are the validation — a malformed descriptor
 * fails at unmarshal time and the host drops that exporter (never crashes).
 *
 * Kinds ([ExporterContract.KIND_SINGLE_CHOICE] / [ExporterContract.KIND_TOGGLE] /
 * [ExporterContract.KIND_PASSPHRASE]):
 * - single-choice: [choiceIds]/[choiceLabels] parallel and non-empty, [defaultValue] one of the ids;
 * - toggle: no choices, [defaultValue] `"0"` or `"1"`;
 * - passphrase: no choices, [defaultValue] `""` — the typed secret is host-collected and
 *   **never crosses** (the spec carries no entry for a passphrase option).
 *
 * Wire form: `String id · String label · int kind · String[] choiceIds · String[] choiceLabels ·
 * String defaultValue` (a compatible tail may be appended in a later version; readers of this
 * version stop after `defaultValue`).
 */
class OptionDescriptor(
    val id: String,
    val label: String,
    val kind: Int,
    val choiceIds: List<String>,
    val choiceLabels: List<String>,
    val defaultValue: String,
) : Parcelable {

    init {
        requireId(id, "option id")
        requireLabel(label, "option label")
        require(kind in ExporterContract.KIND_SINGLE_CHOICE..ExporterContract.KIND_PASSPHRASE) {
            "unknown option kind $kind"
        }
        when (kind) {
            ExporterContract.KIND_SINGLE_CHOICE -> {
                require(choiceIds.isNotEmpty()) { "single-choice option '$id' has no choices" }
                require(choiceIds.size <= ExporterContract.MAX_CHOICES) {
                    "option '$id': ${choiceIds.size} choices > ${ExporterContract.MAX_CHOICES}"
                }
                require(choiceIds.size == choiceLabels.size) {
                    "option '$id': ${choiceIds.size} choice ids vs ${choiceLabels.size} labels"
                }
                require(choiceIds.toSet().size == choiceIds.size) { "option '$id': duplicate choice ids" }
                choiceIds.forEach { requireId(it, "choice id of '$id'") }
                choiceLabels.forEach { requireLabel(it, "choice label of '$id'") }
                require(defaultValue in choiceIds) {
                    "option '$id': default '$defaultValue' is not a declared choice"
                }
            }
            ExporterContract.KIND_TOGGLE -> {
                require(choiceIds.isEmpty() && choiceLabels.isEmpty()) { "toggle option '$id' declares choices" }
                require(defaultValue == "0" || defaultValue == "1") {
                    "toggle option '$id': default must be \"0\" or \"1\""
                }
            }
            ExporterContract.KIND_PASSPHRASE -> {
                require(choiceIds.isEmpty() && choiceLabels.isEmpty()) { "passphrase option '$id' declares choices" }
                require(defaultValue.isEmpty()) { "passphrase option '$id': default must be empty" }
            }
        }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeString(label)
        dest.writeInt(kind)
        dest.writeStringList(choiceIds)
        dest.writeStringList(choiceLabels)
        dest.writeString(defaultValue)
    }

    override fun describeContents(): Int = 0

    companion object {
        internal fun requireId(value: String, what: String) {
            require(value.isNotEmpty() && value.length <= ExporterContract.MAX_ID_CHARS) {
                "$what: length ${value.length} outside 1..${ExporterContract.MAX_ID_CHARS}"
            }
            require(value.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }) {
                "$what: '$value' is not [A-Za-z0-9_-]+"
            }
        }

        internal fun requireLabel(value: String, what: String) {
            require(value.isNotBlank() && value.length <= ExporterContract.MAX_LABEL_CHARS) {
                "$what: blank or over ${ExporterContract.MAX_LABEL_CHARS} chars"
            }
        }

        private fun read(parcel: Parcel): OptionDescriptor {
            val id = parcel.readString() ?: ""
            val label = parcel.readString() ?: ""
            val kind = parcel.readInt()
            val choiceIds = parcel.createStringArrayList() ?: arrayListOf()
            val choiceLabels = parcel.createStringArrayList() ?: arrayListOf()
            val defaultValue = parcel.readString() ?: ""
            return OptionDescriptor(id, label, kind, choiceIds, choiceLabels, defaultValue)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<OptionDescriptor> = object : Parcelable.Creator<OptionDescriptor> {
            override fun createFromParcel(parcel: Parcel): OptionDescriptor = read(parcel)
            override fun newArray(size: Int): Array<OptionDescriptor?> = arrayOfNulls(size)
        }
    }
}
