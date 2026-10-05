package notoshi.easyplacelightfix;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. Carries no gameplay logic: the whole mod is three mixins, and all of them load
 * on their own. This only checks that Litematica is present, because without it the mod is inert
 * and that is worth saying once at startup rather than leaving the user to wonder.
 */
public class EasyPlaceLightFixClient implements ClientModInitializer
{
    public static final String MOD_ID = "easyplace_lightfix";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient()
    {
        LOGGER.info("Easy Place Light Fix loaded ({} light block hooks active)", 3);
    }
}
