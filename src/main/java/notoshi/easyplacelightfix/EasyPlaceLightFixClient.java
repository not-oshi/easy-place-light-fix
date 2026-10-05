package notoshi.easyplacelightfix;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. It carries no gameplay logic: it resolves Litematica's version, prints the
 * diagnostics banner and arranges for a counter summary to be written on exit.
 */
public class EasyPlaceLightFixClient implements ClientModInitializer
{
    public static final String MOD_ID = "easyplace_lightfix";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient()
    {
        LOGGER.info("Initializing EasyPlaceLightFixClient...");
        FabricLoader loader = FabricLoader.getInstance();
        ModContainer litematica = loader.getModContainer("litematica").orElse(null);

        Diagnostics.printHeader(litematica == null
                ? "<not loaded>"
                : litematica.getMetadata().getVersion().getFriendlyString());

        if (litematica == null)
        {
            LOGGER.warn("Litematica not found! Fix will not work without Litematica.");
            Diagnostics.log("INIT", "WARNING: Litematica was not found. The fix does nothing without it.");
        }
        else
        {
            LOGGER.info("Litematica detected: {}", litematica.getMetadata().getVersion().getFriendlyString());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Diagnostics.printSummary();
            LOGGER.debug("EasyPlaceLightFixClient shutdown hook executed");
        }, "easyplace-lightfix-summary"));

        LOGGER.info("EasyPlaceLightFixClient initialized");
    }
}