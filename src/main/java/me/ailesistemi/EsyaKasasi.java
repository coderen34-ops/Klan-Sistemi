package me.ailesistemi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import me.ailesistemi.model.Aile;
import me.ailesistemi.model.AileUyesi;
import me.ailesistemi.model.Rol;

/**
 * Ailenin sayfalı eşya kasası ve oyuncuların bekleyen eşyaları (dağılma payı / ganimet).
 *
 * Her sayfa tek bir ortak Inventory nesnesidir: aynı sayfayı açan herkes aynı envanteri görür,
 * bu yüzden aynı anda açmak eşya kopyalamaya yol açmaz. Sayfanın ilk 45 slotu depo, alt satır geçiş butonlarıdır.
 *
 * Yetki: Üye ve borçlular sadece koyabilir; Yardımcı günlük adet limitiyle alabilir; Patron serbesttir.
 */
public class EsyaKasasi implements Listener {

    public static final int DEPO_SLOT = 45;
    private static final int SLOT_ONCEKI = 45, SLOT_BILGI = 49, SLOT_SONRAKI = 53;

    /** Kasa sayfasını tanımlayan sahip. */
    public static class Sahip implements InventoryHolder {
        public final UUID aileId;
        public final int sayfa;
        private Inventory envanter;

        Sahip(UUID aileId, int sayfa) {
            this.aileId = aileId;
            this.sayfa = sayfa;
        }

        @Override
        public Inventory getInventory() { return envanter; }
    }

    private final AileSistemi plugin;
    private final Map<UUID, List<Inventory>> kasalar = new HashMap<>();
    private final Map<UUID, List<ItemStack>> bekleyenler = new HashMap<>();
    // Yardımcı günlük eşya alımı: oyuncu -> [epochDay, adet]
    private final Map<UUID, long[]> gunlukAlim = new HashMap<>();

    public EsyaKasasi(AileSistemi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }

    private int sayfaSayisi() { return Math.max(1, plugin.getConfig().getInt("kasa.esya-sayfa-sayisi", 3)); }
    private int yardimciLimit() { return plugin.getConfig().getInt("kasa.yardimci-gunluk-esya-limiti", 128); }

    // ------------------------------------------------------------------ SERİLEŞTİRME
    public static String yaz(ItemStack item) {
        return item == null || item.getType() == Material.AIR ? "" : Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    public static ItemStack oku(String veri) {
        if (veri == null || veri.isEmpty()) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(veri));
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ SAYFALAR
    private Inventory sayfaOlustur(Aile a, int sayfa) {
        Sahip sahip = new Sahip(a.id, sayfa);
        Inventory inv = Bukkit.createInventory(sahip, 54,
                m().metin("menu-esya-baslik", "&6&l{aile} &8| &7Eşya Kasası {sayfa}", "aile", a.isim, "sayfa", sayfa + 1));
        sahip.envanter = inv;
        return inv;
    }

    private List<Inventory> sayfalar(Aile a) {
        List<Inventory> liste = kasalar.computeIfAbsent(a.id, k -> new ArrayList<>());
        while (liste.size() < sayfaSayisi()) liste.add(sayfaOlustur(a, liste.size()));
        for (Inventory inv : liste) butonlariKoy(a, inv, ((Sahip) inv.getHolder()).sayfa, liste.size());
        return liste;
    }

    private void butonlariKoy(Aile a, Inventory inv, int sayfa, int toplam) {
        ItemStack cam = buton(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = DEPO_SLOT; i < 54; i++) inv.setItem(i, cam);
        if (sayfa > 0) inv.setItem(SLOT_ONCEKI, buton(Material.ARROW, ChatColor.YELLOW + "◄ Önceki Sayfa", List.of()));
        if (sayfa < toplam - 1) inv.setItem(SLOT_SONRAKI, buton(Material.ARROW, ChatColor.YELLOW + "Sonraki Sayfa ►", List.of()));
        inv.setItem(SLOT_BILGI, buton(Material.CHEST, ChatColor.GOLD + "Sayfa " + (sayfa + 1) + "/" + toplam,
                List.of(ChatColor.GRAY + "Üyeler: sadece koyabilir",
                        ChatColor.GRAY + "Yardımcı: günlük " + yardimciLimit() + " adet alabilir",
                        ChatColor.GRAY + "Patron: serbest")));
    }

    private static ItemStack buton(Material mat, String ad, List<String> aciklama) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ad);
        meta.setLore(aciklama);
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    public void ac(Player p, int sayfa) {
        Aile a = plugin.aileManager().oyuncununAilesi(p.getUniqueId());
        if (a == null) return;
        List<Inventory> liste = sayfalar(a);
        sayfa = Math.max(0, Math.min(sayfa, liste.size() - 1));
        p.openInventory(liste.get(sayfa));
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1f);
    }

    /** Kayıt için: sayfa -> 45 slotluk base64 listesi. */
    public List<List<String>> kayitVerisi(UUID aileId) {
        List<List<String>> veri = new ArrayList<>();
        for (Inventory inv : kasalar.getOrDefault(aileId, List.of())) {
            List<String> sayfa = new ArrayList<>();
            for (int i = 0; i < DEPO_SLOT; i++) sayfa.add(yaz(inv.getItem(i)));
            veri.add(sayfa);
        }
        return veri;
    }

    /** Yükleme: kayıttaki sayfa sayısı config'ten fazlaysa eşyalar kaybolmasın diye sayfalar korunur. */
    public void yukle(Aile a, List<List<String>> veri) {
        List<Inventory> liste = new ArrayList<>();
        for (int s = 0; s < veri.size(); s++) {
            Inventory inv = sayfaOlustur(a, s);
            List<String> sayfa = veri.get(s);
            for (int i = 0; i < Math.min(DEPO_SLOT, sayfa.size()); i++) inv.setItem(i, oku(sayfa.get(i)));
            liste.add(inv);
        }
        kasalar.put(a.id, liste);
    }

    // ------------------------------------------------------------------ DAĞILMA
    /** Aile dağılınca kasadaki eşyalar üyelere sırayla bölünüp bekleyen eşyalarına yazılır. */
    public void dagit(Aile a, List<AileUyesi> uyeler) {
        List<Inventory> liste = kasalar.remove(a.id);
        if (liste == null) return;
        List<ItemStack> esyalar = new ArrayList<>();
        for (Inventory inv : liste) {
            for (HumanEntity izleyen : new ArrayList<>(inv.getViewers())) izleyen.closeInventory();
            for (int i = 0; i < DEPO_SLOT; i++) {
                ItemStack item = inv.getItem(i);
                if (item != null && item.getType() != Material.AIR) esyalar.add(item.clone());
            }
        }
        if (esyalar.isEmpty() || uyeler.isEmpty()) return;
        Collections.shuffle(esyalar);
        for (int i = 0; i < esyalar.size(); i++) {
            AileUyesi u = uyeler.get(i % uyeler.size());
            bekleyenEkle(u.uuid, esyalar.get(i));
        }
        for (AileUyesi u : uyeler) {
            Player p = Bukkit.getPlayer(u.uuid);
            if (p != null) m().gonder(p, "dagilma-esya", "&eAile eşya kasasından payınıza düşen eşyalar ayrıldı. Almak için: &6/aile ganimet");
        }
        plugin.log().yaz(a, "-", "ESYA_DAGITILDI", esyalar.size() + " yığın " + uyeler.size() + " üyeye");
    }

    // ------------------------------------------------------------------ BEKLEYEN EŞYALAR
    public void bekleyenEkle(UUID oyuncu, ItemStack item) {
        bekleyenler.computeIfAbsent(oyuncu, k -> new ArrayList<>()).add(item);
    }

    public Map<UUID, List<ItemStack>> bekleyenler() { return bekleyenler; }

    public int bekleyenSayisi(UUID oyuncu) { return bekleyenler.getOrDefault(oyuncu, List.of()).size(); }

    /** /aile ganimet: bekleyen eşyalardan envantere sığanları verir, kalanı bekletir. */
    public void bekleyenleriVer(Player p) {
        List<ItemStack> liste = bekleyenler.get(p.getUniqueId());
        if (liste == null || liste.isEmpty()) { m().gonder(p, "bekleyen-yok", "&7Bekleyen eşyanız yok."); return; }
        int verilen = 0;
        List<ItemStack> kalan = new ArrayList<>();
        for (ItemStack item : liste) {
            Map<Integer, ItemStack> sigmayan = p.getInventory().addItem(item.clone());
            if (sigmayan.isEmpty()) verilen++;
            else kalan.addAll(sigmayan.values());
        }
        if (kalan.isEmpty()) bekleyenler.remove(p.getUniqueId());
        else bekleyenler.put(p.getUniqueId(), kalan);
        plugin.veri().kaydet();
        plugin.log().yaz(plugin.aileManager().oyuncununAilesi(p.getUniqueId()), p.getName(), "BEKLEYEN_ALDI", verilen + " yığın, kalan " + kalan.size());
        if (kalan.isEmpty()) m().gonder(p, "bekleyen-alindi", "&aBekleyen tüm eşyalarınızı aldınız.");
        else m().gonder(p, "bekleyen-kismen", "&eEnvanteriniz doldu. {kalan} yığın eşya hâlâ bekliyor, yer açıp tekrar &6/aile ganimet&e yazın.", "kalan", kalan.size());
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
    }

    // ------------------------------------------------------------------ YETKİ KONTROLLÜ TIKLAMALAR
    private enum Erisim { KOYABILIR, LIMITLI_ALABILIR, SERBEST }

    private Erisim erisim(Aile a, AileUyesi u) {
        if (u.borclu || u.rol == Rol.UYE) return Erisim.KOYABILIR;
        if (u.rol == Rol.YARDIMCI) return Erisim.LIMITLI_ALABILIR;
        return Erisim.SERBEST;
    }

    private int bugunAlinan(UUID oyuncu) {
        long[] k = gunlukAlim.get(oyuncu);
        long bugun = LocalDate.now().toEpochDay();
        return k == null || k[0] != bugun ? 0 : (int) k[1];
    }

    private void alimEkle(UUID oyuncu, int adet) {
        long bugun = LocalDate.now().toEpochDay();
        long[] k = gunlukAlim.get(oyuncu);
        if (k == null || k[0] != bugun) gunlukAlim.put(oyuncu, new long[]{bugun, adet});
        else k[1] += adet;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Sahip sahip)) return;
        if (!(event.getWhoClicked() instanceof Player p)) { event.setCancelled(true); return; }

        Aile a = plugin.aileManager().oyuncununAilesi(p.getUniqueId());
        if (a == null || !a.id.equals(sahip.aileId)) { event.setCancelled(true); p.closeInventory(); return; }
        if (plugin.kasaKilitliMi(a)) {
            event.setCancelled(true);
            m().gonder(p, "kasa-kilitli", "&cSavaş sürerken aile kasası kilitlidir.");
            return;
        }
        AileUyesi u = a.uyeler.get(p.getUniqueId());
        int ust = event.getView().getTopInventory().getSize();
        int slot = event.getRawSlot();
        InventoryAction aksiyon = event.getAction();

        // Alt satır: sayfa butonları
        if (slot >= DEPO_SLOT && slot < ust) {
            event.setCancelled(true);
            if (slot == SLOT_ONCEKI && sahip.sayfa > 0) ac(p, sahip.sayfa - 1);
            else if (slot == SLOT_SONRAKI) ac(p, sahip.sayfa + 1);
            return;
        }

        Erisim erisim = erisim(a, u);
        boolean ustte = slot >= 0 && slot < ust;

        if (!ustte) {
            // Oyuncunun kendi envanteri. Shift ile kasaya koymak serbest.
            // "Aynı türleri topla" kasadan da çekebileceği için sadece patrona açık.
            if (aksiyon == InventoryAction.COLLECT_TO_CURSOR && erisim != Erisim.SERBEST) event.setCancelled(true);
            if (aksiyon == InventoryAction.MOVE_TO_OTHER_INVENTORY) logla(a, p, "ESYA_KOYDU", event.getCurrentItem());
            return;
        }

        // Kasa slotu: koyma işlemleri herkese açık
        switch (aksiyon) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME -> {
                logla(a, p, "ESYA_KOYDU", event.getCursor());
                return;
            }
            case NOTHING -> { return; }
            default -> { }
        }

        // Buradan sonrası kasadan alma (ya da alma içeren takas)
        if (erisim == Erisim.SERBEST) {
            if (event.getCurrentItem() != null && event.getCurrentItem().getType() != Material.AIR) logla(a, p, "ESYA_ALDI", event.getCurrentItem());
            return;
        }
        if (erisim == Erisim.KOYABILIR) {
            event.setCancelled(true);
            if (u.borclu) m().gonder(p, "esya-borclu", "&cAidat borcunuz olduğu için kasadan eşya alamazsınız.");
            else m().gonder(p, "esya-uye", "&cÜyeler kasaya sadece eşya koyabilir.");
            return;
        }
        // Yardımcı: sadece düz alma ve shift ile alma, günlük limitle
        ItemStack hedef = event.getCurrentItem();
        if (hedef == null || hedef.getType() == Material.AIR) return;
        int adet;
        switch (aksiyon) {
            case PICKUP_ALL, MOVE_TO_OTHER_INVENTORY -> adet = hedef.getAmount();
            case PICKUP_HALF -> adet = (hedef.getAmount() + 1) / 2;
            case PICKUP_ONE -> adet = 1;
            default -> {
                event.setCancelled(true);
                m().gonder(p, "esya-yardimci-islem", "&cYardımcılar kasadan sadece tıklayarak ya da Shift ile eşya alabilir.");
                return;
            }
        }
        int kalan = yardimciLimit() - bugunAlinan(p.getUniqueId());
        if (adet > kalan) {
            event.setCancelled(true);
            m().gonder(p, "esya-limit", "&cGünlük eşya alma limitiniz: {kalan} adet kaldı.", "kalan", Math.max(0, kalan));
            return;
        }
        alimEkle(p.getUniqueId(), adet);
        logla(a, p, "ESYA_ALDI", hedef);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Sahip)) return;
        // Sürükleyerek koymak serbest, ama buton satırına bırakılamaz
        for (int slot : event.getRawSlots()) {
            if (slot >= DEPO_SLOT && slot < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Sahip) plugin.veri().kaydet();
    }

    private void logla(Aile a, Player p, String islem, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return;
        plugin.log().yaz(a, p.getName(), islem, item.getAmount() + "x " + item.getType().name());
    }

    /** Aileden çıkarılan oyuncu açık kasayı kullanmaya devam edemesin. */
    public void kapat(Player p) {
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof Sahip) p.closeInventory();
    }
}
