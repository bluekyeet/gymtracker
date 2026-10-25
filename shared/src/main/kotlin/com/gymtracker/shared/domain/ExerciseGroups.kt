package com.gymtracker.shared.domain

val MuscleGroup.label: String
    get() = when (this) {
        MuscleGroup.CHEST -> "Chest"
        MuscleGroup.BACK -> "Back"
        MuscleGroup.SHOULDERS -> "Shoulders"
        MuscleGroup.ARMS -> "Arms"
        MuscleGroup.LEGS -> "Legs"
        MuscleGroup.CORE -> "Core"
        MuscleGroup.FULL_BODY -> "Full body"
        MuscleGroup.OTHER -> "Other"
    }

/** What the UI shows for an exercise's group: the user's own name wins over the built-in one. */
val Exercise.groupName: String?
    get() = customGroup?.takeIf { it.isNotBlank() } ?: muscleGroup?.label

/**
 * Turns a typed group name into either a built-in group or a custom one.
 * Blank input resolves to neither.
 */
fun resolveGroup(name: String?): Pair<MuscleGroup?, String?> {
    val trimmed = name?.trim().orEmpty()
    if (trimmed.isEmpty()) return null to null
    val builtIn = MuscleGroup.entries.firstOrNull { it.label.equals(trimmed, ignoreCase = true) }
    return if (builtIn != null) builtIn to null else null to trimmed
}
