package me.klansistemi;

import org.bukkit.OfflinePlayer;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;

/**
 * PlaceholderAPI değerleri (PlaceholderAPI sunucuda kuruluysa otomatik kaydolur):
 *   %klan_isim%     Klan adı (klanı yoksa boş)
 *   %klan_rol%      Lider / Yardımcı / Üye
 *   %klan_uye%      Klandaki üye sayısı
 *   %klan_prestij%  Klanın prestij puanı
 *   %klan_sira%     Prestij sıralamasındaki yeri
 */
public class KlanPlaceholder extends PlaceholderExpansion {

    private final KlanSistemi plugin;

    public KlanPlaceholder(KlanSistemi plugin) {
        this.plugin = plugin;
    }

    @Override public String getIdentifier() { return "klan"; }
    @Override public String getAuthor() { return "coderen34-ops"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer oyuncu, String deger) {
        if (oyuncu == null) return "";
        Klan k = plugin.klanManager().oyuncununKlani(oyuncu.getUniqueId());
        String bos = plugin.getConfig().getString("placeholder.klansiz", "");
        if (k == null) return bos;
        KlanUyesi u = k.uyeler.get(oyuncu.getUniqueId());
        return switch (deger.toLowerCase(java.util.Locale.ROOT)) {
            case "isim" -> k.isim;
            case "rol" -> u != null ? u.rol.ad : bos;
            case "uye" -> String.valueOf(k.uyeler.size());
            case "prestij" -> String.valueOf(k.prestij);
            case "sira" -> String.valueOf(plugin.prestij().sira(k));
            default -> null;
        };
    }
}
