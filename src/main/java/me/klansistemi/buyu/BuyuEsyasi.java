package me.klansistemi.buyu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import me.klansistemi.KlanSistemi;
import me.klansistemi.Mesaj;
import me.klansistemi.Zaman;

/**
 * Büyü uzmanlığı eşyalarının tanımı ve kimliği.
 *
 * Eşyanın kimliği eşyanın kendi içinde (PDC) saklanır: kısa kod (lore'da görünen), UUID, kaynak klan,
 * alan, katman (KLAN / TICARI) ve üretim tarihi. Lore her zaman PDC'den yeniden üretilir.
 */
public class BuyuEsyasi {

    public enum Katman { KLAN, TICARI }

    /** config.yml -> buyu.alanlar.<ALAN> */
    public static class AlanTanimi {
        public final String kod;            // KAZMA, KILIC ...
        public final String ad;             // Görünen ad (renkli)
        public final boolean aktif;
        public final Set<Material> esyalar;
        public final Map<Enchantment, Integer> klanSeviye;
        public final Map<Enchantment, Integer> ticariSeviye;

        AlanTanimi(String kod, String ad, boolean aktif, Set<Material> esyalar,
                   Map<Enchantment, Integer> klanSeviye, Map<Enchantment, Integer> ticariSeviye) {
            this.kod = kod;
            this.ad = ad;
            this.aktif = aktif;
            this.esyalar = esyalar;
            this.klanSeviye = klanSeviye;
            this.ticariSeviye = ticariSeviye;
        }

        public Map<Enchantment, Integer> seviyeler(Katman k) {
            return k == Katman.KLAN ? klanSeviye : ticariSeviye;
        }
    }

    private final KlanSistemi plugin;
    public final NamespacedKey idKey, uuidKey, klanKey, klanAdiKey, alanKey, katmanKey, tarihKey, eleGecirenKey;

    public BuyuEsyasi(KlanSistemi plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "buyu_id");
        this.uuidKey = new NamespacedKey(plugin, "buyu_uuid");
        this.klanKey = new NamespacedKey(plugin, "buyu_klan");
        this.klanAdiKey = new NamespacedKey(plugin, "buyu_klan_adi");
        this.alanKey = new NamespacedKey(plugin, "buyu_alan");
        this.katmanKey = new NamespacedKey(plugin, "buyu_katman");
        this.tarihKey = new NamespacedKey(plugin, "buyu_tarih");
        this.eleGecirenKey = new NamespacedKey(plugin, "buyu_ele_geciren");
    }

    // ------------------------------------------------------------------ ALAN TANIMLARI
    /** config'teki tüm alanlar (aktif olmayanlar dahil), sırası korunur. */
    public Map<String, AlanTanimi> alanlar() {
        Map<String, AlanTanimi> sonuc = new LinkedHashMap<>();
        ConfigurationSection bolum = plugin.getConfig().getConfigurationSection("buyu.alanlar");
        if (bolum == null) return sonuc;
        for (String kod : bolum.getKeys(false)) {
            ConfigurationSection c = bolum.getConfigurationSection(kod);
            if (c == null) continue;
            Set<Material> esyalar = new LinkedHashSet<>();
            for (String m : c.getStringList("esyalar")) {
                Material mat = Material.matchMaterial(m);
                if (mat != null) esyalar.add(mat);
                else plugin.getLogger().warning("[Büyü] " + kod + " alanında bilinmeyen eşya: " + m);
            }
            sonuc.put(kod.toUpperCase(Locale.ROOT), new AlanTanimi(kod.toUpperCase(Locale.ROOT),
                    Mesaj.renk(c.getString("ad", kod)), c.getBoolean("aktif", false), esyalar,
                    seviyeOku(kod, c.getConfigurationSection("klan")), seviyeOku(kod, c.getConfigurationSection("ticari"))));
        }
        return sonuc;
    }

    public AlanTanimi alan(String kod) {
        return kod == null ? null : alanlar().get(kod.toUpperCase(Locale.ROOT));
    }

    private Map<Enchantment, Integer> seviyeOku(String alan, ConfigurationSection c) {
        Map<Enchantment, Integer> sonuc = new LinkedHashMap<>();
        if (c == null) return sonuc;
        for (String isim : c.getKeys(false)) {
            Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(isim.toLowerCase(Locale.ROOT)));
            if (e == null) {
                plugin.getLogger().warning("[Büyü] " + alan + " alanında bilinmeyen büyü: " + isim);
                continue;
            }
            sonuc.put(e, Math.max(1, c.getInt(isim)));
        }
        return sonuc;
    }

    // ------------------------------------------------------------------ ÜRETİM
    /** Yeni eşya üretir ve kimliğini eşyaya yazar (kayıt defterine eklemek çağıranın işidir). */
    public ItemStack olustur(Material mat, AlanTanimi alan, Katman katman, UUID klanId, String klanAdi, String kisaKod, UUID uuid, long tarih) {
        ItemStack item = new ItemStack(mat);
        for (Map.Entry<Enchantment, Integer> e : alan.seviyeler(katman).entrySet()) {
            // Eşyaya uygun olmayan büyü (örn. kazmaya Keskinlik) basılmaz
            if (e.getKey().canEnchantItem(item)) item.addUnsafeEnchantment(e.getKey(), e.getValue());
        }
        kimlikYaz(item, alan.kod, katman, klanId, klanAdi, kisaKod, uuid, tarih);
        return item;
    }

    /** Var olan bir eşyaya büyü kimliğini yazar (ticari satışta alıcının kendi eşyası için). */
    public void kimlikYaz(ItemStack item, String alanKod, Katman katman, UUID klanId, String klanAdi, String kisaKod, UUID uuid, long tarih) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(idKey, PersistentDataType.STRING, kisaKod);
        pdc.set(uuidKey, PersistentDataType.STRING, uuid.toString());
        pdc.set(klanKey, PersistentDataType.STRING, klanId.toString());
        pdc.set(klanAdiKey, PersistentDataType.STRING, klanAdi);
        pdc.set(alanKey, PersistentDataType.STRING, alanKod);
        pdc.set(katmanKey, PersistentDataType.STRING, katman.name());
        pdc.set(tarihKey, PersistentDataType.LONG, tarih);
        item.setItemMeta(meta);
        loreYenile(item);
    }

    /** Eşyanın kaynak klanı (kimliği yoksa null). */
    public UUID kaynakKlan(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String k = item.getItemMeta().getPersistentDataContainer().get(klanKey, PersistentDataType.STRING);
        return k == null ? null : UUID.fromString(k);
    }

    // ------------------------------------------------------------------ OKUMA
    public boolean buyuluMu(ItemStack item) {
        return kisaKod(item) != null;
    }

    public String kisaKod(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    public String uuidMetni(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(uuidKey, PersistentDataType.STRING);
    }

    public Katman katman(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String k = item.getItemMeta().getPersistentDataContainer().get(katmanKey, PersistentDataType.STRING);
        try { return k == null ? null : Katman.valueOf(k); } catch (IllegalArgumentException e) { return null; }
    }

    /** Klan katmanı (kasa-bağlı) eşya mı? */
    public boolean kasaBagliMi(ItemStack item) {
        return katman(item) == Katman.KLAN;
    }

    // ------------------------------------------------------------------ LORE
    /**
     * Lore'u PDC ve kayıt defterinden yeniden yazar:
     *   Büyü Kaynağı: <Klan> [(Dağılmış Klan)] | [Ticari |] ID: AB12CD
     *   Ele Geçirildi: <Klan> (varsa)
     */
    public void loreYenile(ItemStack item) {
        String kod = kisaKod(item);
        if (kod == null) return;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String klanAdi = pdc.getOrDefault(klanAdiKey, PersistentDataType.STRING, "?");
        String klanId = pdc.get(klanKey, PersistentDataType.STRING);
        boolean dagilmis = klanId == null || plugin.klanManager().klanGetir(UUID.fromString(klanId)) == null;
        Katman katman = katman(item);
        String alanKod = pdc.get(alanKey, PersistentDataType.STRING);
        AlanTanimi alan = alan(alanKod);
        Long tarih = pdc.get(tarihKey, PersistentDataType.LONG);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "----------------------");
        lore.add(ChatColor.GRAY + "Büyü Kaynağı: " + ChatColor.GOLD + klanAdi + (dagilmis ? ChatColor.DARK_GRAY + " (Dağılmış Klan)" : ""));
        lore.add(ChatColor.GRAY + (katman == Katman.TICARI ? "Ticari | " : "") + "ID: " + ChatColor.WHITE + kod);
        if (alan != null) lore.add(ChatColor.GRAY + "Alan: " + alan.ad);
        if (tarih != null) lore.add(ChatColor.DARK_GRAY + "Üretim: " + Zaman.tarih(tarih));
        String eleGeciren = pdc.get(eleGecirenKey, PersistentDataType.STRING);
        if (eleGeciren != null) lore.add(ChatColor.RED + "Ele Geçirildi: " + eleGeciren);
        if (katman == Katman.KLAN) lore.add(ChatColor.DARK_AQUA + "Klan kasasına bağlıdır");
        meta.setLore(lore);
        item.setItemMeta(meta);
    }
}
