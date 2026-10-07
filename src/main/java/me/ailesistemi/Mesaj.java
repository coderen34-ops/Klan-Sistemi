package me.ailesistemi;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Tüm oyuncu mesajları config.yml'deki "mesajlar" bölümünden okunur (& renk kodları desteklenir).
 * Config'te anahtar yoksa koddaki varsayılan metin kullanılır. {isim} biçimindeki yer tutucular doldurulur.
 */
public class Mesaj {

    private final AileSistemi plugin;

    public Mesaj(AileSistemi plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public String onek() {
        return renk(cfg().getString("mesajlar.onek", "&8[&6Aile&8] &r"));
    }

    public static String renk(String metin) {
        return ChatColor.translateAlternateColorCodes('&', metin);
    }

    /** Ham (öneksiz) renkli metin. yerTutucular: "anahtar", değer, "anahtar2", değer2 ... */
    public String metin(String anahtar, String varsayilan, Object... yerTutucular) {
        String m = cfg().getString("mesajlar." + anahtar, varsayilan);
        for (int i = 0; i + 1 < yerTutucular.length; i += 2) {
            m = m.replace("{" + yerTutucular[i] + "}", String.valueOf(yerTutucular[i + 1]));
        }
        return renk(m);
    }

    public void gonder(CommandSender alici, String anahtar, String varsayilan, Object... yerTutucular) {
        if (alici == null) return;
        alici.sendMessage(onek() + metin(anahtar, varsayilan, yerTutucular));
    }

    /** Tıklanabilir bileşenlere eklemek için öneksiz metin. */
    public Component bilesen(String anahtar, String varsayilan, Object... yerTutucular) {
        return LegacyComponentSerializer.legacySection().deserialize(metin(anahtar, varsayilan, yerTutucular));
    }

    public Component onekBileseni() {
        return LegacyComponentSerializer.legacySection().deserialize(onek());
    }
}
