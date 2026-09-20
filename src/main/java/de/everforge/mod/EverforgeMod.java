package de.everforge.mod;

import de.everforge.mod.server.audit.PlayerCommandAudit;
import de.everforge.mod.server.opac.OpenPacAutoMode;
import de.everforge.mod.server.opac.OpenPacServerForceloadHotfix;
import de.everforge.mod.server.worldedit.WorldEditOpenPacIntegration;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

/**
 * Common entry point for the Everforge Mod.
 *
 * Client-only branding lives below de.everforge.mod.client. Optional server
 * integrations are installed only when their runtime mods are present.
 */
@Mod(EverforgeMod.MOD_ID)
public final class EverforgeMod {
    public static final String MOD_ID = "everforge_mod";

    public EverforgeMod() {
        PlayerCommandAudit.install();

        ModList mods = ModList.get();
        if (mods.isLoaded("openpartiesandclaims")) {
            OpenPacServerForceloadHotfix.install();
            OpenPacAutoMode.install();
        }
        if (mods.isLoaded("worldedit") && mods.isLoaded("openpartiesandclaims")) {
            WorldEditOpenPacIntegration.install();
        }
    }
}
