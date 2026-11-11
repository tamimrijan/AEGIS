package com.aegis.vault.logic

/**
 * Built-in word list for memorable passphrases. Entropy shown in the app is always computed
 * from the real size of this list (log2(size) bits per word), never assumed.
 */
object Wordlist {
    val words: List<String> = """
        able acid acorn actor adapt adobe agent agile alarm album alert alley alloy alpha amber ample
        anchor angle ankle apple apron arbor arch arena armor arrow aspen atlas atom attic audio autumn
        badge bagel baker balmy bamboo banjo barn basil basin batch beach beacon beam bean bench berry
        birch bison blade blank blaze blend bloom board boat bold bolt bonus book boost booth border
        bottle boulder brave bread breeze brick bridge bright brisk broad bronze brook brush bubble bucket
        buddy bugle build bundle cabin cable cactus cadet camel camera canal candle canoe canvas canyon
        carbon cargo carpet carve castle cedar cellar chalk charm chart chase cheer cherry chess chief
        chime chisel cider cinder circle civic clay clever cliff climb clock cloud clover coach coast
        cobalt cocoa comet copper coral cotton cozy crane crater creek crest crisp crown crystal cubic
        curve cycle daisy dance dapper dawn decoy delta denim depot desert diary digit diner dock dolphin
        domino donor dove draft dragon dream drift drum dune eagle early earth easel echo edge elbow
        elder elm ember emblem empire engine envoy epoch equal ether event exact fable fabric falcon
        fancy fauna feast fern ferry fiber field fig final finch fjord flag flame flask fleet flint
        float flora flute focus forest forge fossil frame fresh frost fruit fuel gadget galaxy garden
        garnet gate gecko gentle ginger glacier glade glass glide globe glow goblet gold grain grand
        granite grape grass gravel green grove guide guitar gust habit hammer harbor hardy harvest hatch
        haven hazel heart heron hill hinge holly honey horizon hotel humble husky icon igloo index
        indigo inlet iris iron island ivory ivy jacket jade jasper jazz jelly jewel jolly journal
        jungle juniper kayak kernel kettle kite knight koala label lagoon lake lamp lantern larch laser
        latch lava lawn leaf ledger lemon level lever light lilac lime linen lion lizard lodge logic
        lotus lucky lunar lyric magma magnet mango maple marble march market marsh mason meadow medal
        melon mentor mesa metal meteor mint mirror mist mocha model molar moss motor mountain mural
        museum music nacho navy nectar nest nickel noble north novel nylon oasis ocean olive onyx opal
        orbit orchid organ otter outer oval oxide oyster paddle paint palm panda panel paper parade
        parcel park pastel patch path pearl pebble pecan pedal pepper petal piano pilot pine pivot
        pixel plain planet plaza plum pocket poem polar pond poppy portal potato prairie prism proud
        pulse pumpkin puzzle quail quartz quest quiet quill quilt radar raft rain rally ranch rapid
        raven reef region relay relic resin ribbon ridge river robin rocket rose rover royal ruby rustic
        saddle safari sage sail salad salmon sand satin scale scarf scout seal season sedan seed shade
        shell shield shore silk silver sketch skill slate sled smile smoke snow solar sonic spark
        spice spiral spoke spruce square stable stamp star steam steel stone storm story straw stream
        studio summit sunny swan swift table talon tango tartan temple tender tent terra thistle thorn
        thunder tiger timber toast token topaz torch tower trail train tulip tundra tunnel turbo turtle
        twig ultra umbra union unity upper urban valley valve vapor velvet venture verse vessel vigor
        villa vine violet vista vivid voyage wagon walnut waltz water wave wheat whale whisper wicker
        willow wind window winter wisdom wolf wonder wood woven yacht yarn yellow yoga zebra zenith
        zephyr zinc zone
    """.split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()
}
