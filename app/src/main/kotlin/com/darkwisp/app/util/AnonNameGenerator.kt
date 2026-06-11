package com.darkwisp.app.util

object AnonNameGenerator {
    private val adjectives = listOf(
        "Ghost", "Silent", "Shadow", "Neon", "Void", "Cryptic", "Dark", "Frost",
        "Hollow", "Feral", "Fractal", "Static", "Phantom", "Dusk", "Drift", "Echo",
        "Lost", "Blind", "Wisp", "Swift", "Grim", "Pale", "Cold", "Deep", "Raw",
        "Bare", "Null", "Zero", "Pure", "Sharp", "Vague", "Vast", "Still", "Lite",
        "Grey", "Faint", "Keen", "Dull", "Rogue", "Lone", "Mad", "Wild", "Free",
        "Sly", "Thin", "Bare", "Cool", "Warm", "Dark", "Fake", "True", "Blunt",
        "Slick", "Gloss", "Smooth", "Rough", "Blank", "Empty", "Solid", "Fluid",
        "Quick", "Slow", "Light", "Heavy", "Taut", "Loose", "Brittle", "Tender",
        "Bitter", "Sour", "Sweet", "Salty", "Mild", "Spice", "Damp", "Dry",
        "Dense", "Sparse", "Terse", "Loud", "Mute", "Blind", "Deaf", "Dumb",
        "Still", "Calm", "Rapid", "Steady", "Fleet", "Slant", "Crook", "Stark",
        "Plain", "Grand", "Petty", "Noble", "Base", "Prime", "Odd", "Even",
        "Dire", "Grim", "Sage", "Wise", "Mad", "Balmy", "Sere", "Rife"
    )

    private val nouns = listOf(
        "Signal", "Packet", "Wisp", "Mask", "Drifter", "Cipher", "Shade",
        "Nomad", "Ghost", "Prism", "Blade", "Pulse", "Flux", "Node", "Path",
        "Gate", "Wire", "Core", "Edge", "Hash", "Code", "Key", "Seed", "Byte",
        "Bit", "Cell", "Frame", "Wave", "Peak", "Grid", "Root", "Ring", "Loop",
        "Link", "Mark", "Sign", "Trace", "Trail", "Vein", "Web", "Torch",
        "Lens", "Spark", "Flame", "Beacon", "Lantern", "Candle", "Star",
        "Moon", "Tide", "Current", "Stream", "River", "Ocean", "Depth",
        "Aether", "Vapor", "Smoke", "Ash", "Ember", "Cinder", "Glow",
        "Radiance", "Shadow", "Umbra", "Penumbra", "Shard", "Fragment",
        "Splinter", "Needle", "Pin", "Hook", "Claw", "Fang", "Horn",
        "Spine", "Ridge", "Crest", "Tower", "Spire", "Pillar", "Stone",
        "Crystal", "Gem", "Ore", "Metal", "Steel", "Iron", "Copper",
        "Tin", "Zinc", "Lead", "Gold", "Silver", "Bronze", "Brass",
        "Alloy", "Chain", "Mesh", "Net", "Web", "Weave", "Thread"
    )

    fun generate(): String {
        val adj = adjectives.random()
        val noun = nouns.random()
        val num = (100..999).random()
        return "${adj}${noun}$num"
    }
}
