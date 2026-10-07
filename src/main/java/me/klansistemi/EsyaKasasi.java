package me.klansistemi;

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

import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;
import me.klansistemi.model.Rol;

/**
 * Klanın sayfalı eşya kasası ve oyuncuların bekleyen eşyaları (dağılma payı / ganimet).
 *
 * Her sayfa tek bir ortak Inventory nesnesidir: aynı sayfayı açan herkes aynı envanteri görür,
 * bu yüzden aynı anda açmak eşya kopyalamaya yol açmaz. Sayfanın ilk 45 slotu depo, alt satır geçiş butonlarıdır.
 *
 * Yetki: Üye ve borçlular sadece koyabilir; Yardımcı günlük adet limitiyle alabilir; Lider serbesttir.
 */
public class EsyaKasasi implements Listener {

    public static final int DEPO_SLOT = 45;
    private static final int SLOT_ONCEKI = 45, SLOT_BILGI = 49, SLOT_SONRAKI = 53;

    /** Kasa sayfasını tanımlayan sahip. */
    public static class Sahip implements InventoryHolder {
        public final UUID klanId;
        public final int sayfa;
        private Inventory envanter;

        Sahip(UUID klanId, int sayfa) {
            this.klanId = klanId;
            this.sayfa = sayfa;
        }

        @Override
        public Inventory getInventory() { return envanter; }
    }

    private final KlanSistemi plugin;
    private final Map<UUID, List<Inventory>> kasalar = new HashMap<>();
    private final Map<UUID, List<ItemStack>> bekleyenler = new HashMap<>();
    // Yardımcı günlük eşya alımı: oyuncu -> [epochDay, adet]
    private final Map<UUID, long[]> gunlukAlim = new HashMap<>();

    public EsyaKasasi(KlanSistemi plugin) {
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
    private Inventory sayfaOlustur(Klan a, int sayfa) {
        Sahip sahip = new Sahip(a.id, sayfa);
        Inventory inv = Bukkit.createInventory(sahip, 54,
                m().metin("menu-esya-baslik", "&6&l{klan} &8| &7Eşya Kasası {sayfa}", "klan", a.isim, "sayfa", sayfa + 1));
        sahip.envanter = inv;
        return inv;
    }

    private List<Inventory> sayfalar(Klan a) {
        List<Inventory> liste = kasalar.computeIfAbsent(a.id, k -> new ArrayList<>());
        while (liste.size() < sayfaSayisi()) liste.add(sayfaOlustur(a, liste.size()));
        for (Inventory inv : liste) butonlariKoy(a, inv, ((Sahip) inv.getHolder()).sayfa, liste.size());
        return liste;
    }

    private void butonlariKoy(Klan a, Inventory inv, int sayfa, int toplam) {
        ItemStack cam = buton(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = DEPO_SLOT; i < 54; i++) inv.setItem(i, cam);
        if (sayfa > 0) inv.setItem(SLOT_ONCEKI, buton(Material.ARROW, ChatColor.YELLOW + "◄ Önceki Sayfa", List.of()));
        if (sayfa < toplam - 1) inv.setItem(SLOT_SONRAKI, buton(Material.ARROW, ChatColor.YELLOW + "Sonraki Sayfa ►", List.of()));
        inv.setItem(SLOT_BILGI, buton(Material.CHEST, ChatColor.GOLD + "Sayfa " + (sayfa + 1) + "/" + toplam,
                List.of(ChatColor.GRAY + "Üyeler: sadece koyabilir",
                        ChatColor.GRAY + "Yardımcı: günlük " + yardimciLimit() + " adet alabilir",
                        ChatColor.GRAY + "Lider: serbest")));
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
        Klan a = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (a == null) return;
        List<Inventory> liste = sayfalar(a);
        sayfa = Math.max(0, Math.min(sayfa, liste.size() - 1));
        p.openInventory(liste.get(sayfa));
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1f);
    }

    /** Kayıt için: sayfa -> 45 slotluk base64 listesi. */
    public List<List<String>> kayitVerisi(UUID klanId) {
        List<List<String>> veri = new ArrayList<>();
        for (Inventory inv : kasalar.getOrDefault(klanId, List.of())) {
            List<String> sayfa = new ArrayList<>();
            for (int i = 0; i < DEPO_SLOT; i++) sayfa.add(yaz(inv.getItem(i)));
            veri.add(sayfa);
        }
        return veri;
    }

    /** Yükleme: kayıttaki sayfa sayısı config'ten fazlaysa eşyalar kaybolmasın diye sayfalar korunur. */
    public void yukle(Klan a, List<List<String>> veri) {
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
    /** Klan dağılınca kasadaki eşyalar üyelere sırayla bölünüp bekleyen eşyalarına yazılır. */
    public void dagit(Klan a, List<KlanUyesi> uyeler) {
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
            KlanUyesi u = uyeler.get(i % uyeler.size());
            bekleyenEkle(u.uuid, esyalar.get(i));
        }
        for (KlanUyesi u : uyeler) {
            Player p = Bukkit.getPlayer(u.uuid);
            if (p != null) m().gonder(p, "dagilma-esya", "&eKlan eşya kasasından payınıza düşen eşyalar ayrıldı. Almak için: &6/klan ganimet");
        }
        plugin.log().yaz(a, "-", "ESYA_DAGITILDI", esyalar.size() + " yığın " + uyeler.size() + " üyeye");
    }

    // ------------------------------------------------------------------ GANİMET
    /**
     * Kaybeden klanın kasasındaki dolu slotlardan rastgele %oran kadarını kazanan klana taşır (tamamı asla değil).
     * Kazananın kasası dolarsa kalanlar kazanan liderin bekleyen eşyalarına yazılır (/klan ganimet).
     * @return taşınan yığın sayısı
     */
    public int ganimetAktar(Klan kaybeden, Klan kazanan, double oranYuzde) {
        List<Inventory> kaynak = sayfalar(kaybeden);
        List<int[]> doluSlotlar = new ArrayList<>(); // {sayfa, slot}
        for (int s = 0; s < kaynak.size(); s++) {
            for (int i = 0; i < DEPO_SLOT; i++) {
                ItemStack item = kaynak.get(s).getItem(i);
                if (item != null && item.getType() != Material.AIR) doluSlotlar.add(new int[]{s, i});
            }
        }
        int adet = (int) Math.round(doluSlotlar.size() * oranYuzde / 100.0);
        if (adet >= doluSlotlar.size() && doluSlotlar.size() > 0) adet = doluSlotlar.size() - 1; // Tamamı alınmaz
        if (adet <= 0) return 0;
        Collections.shuffle(doluSlotlar);

        List<Inventory> hedef = sayfalar(kazanan);
        KlanUyesi lider = kazanan.lider();
        int bekleyeneGiden = 0;
        for (int k = 0; k < adet; k++) {
            int[] yer = doluSlotlar.get(k);
            Inventory inv = kaynak.get(yer[0]);
            ItemStack item = inv.getItem(yer[1]);
            inv.setItem(yer[1], null);
            if (item == null) continue;
            ItemStack kalan = item;
            for (Inventory h : hedef) {
                kalan = depoyaKoy(h, kalan);
                if (kalan == null) break;
            }
            if (kalan != null && lider != null) {
                bekleyenEkle(lider.uuid, kalan);
                bekleyeneGiden++;
            }
        }
        if (bekleyeneGiden > 0 && lider != null) {
            Player pp = Bukkit.getPlayer(lider.uuid);
            if (pp != null) m().gonder(pp, "ganimet-bekleyen", "&6Kasanız dolu olduğu için {sayi} yığın ganimet size ayrıldı: &e/klan ganimet", "sayi", bekleyeneGiden);
        }
        return adet;
    }

    /** Eşyayı sayfanın sadece depo slotlarına (alt buton satırına değil) koyar; sığmayanı döndürür. */
    private static ItemStack depoyaKoy(Inventory inv, ItemStack item) {
        ItemStack kalan = item.clone();
        for (int i = 0; i < DEPO_SLOT && kalan.getAmount() > 0; i++) {
            ItemStack mevcut = inv.getItem(i);
            if (mevcut != null && mevcut.isSimilar(kalan) && mevcut.getAmount() < mevcut.getMaxStackSize()) {
                int eklenecek = Math.min(kalan.getAmount(), mevcut.getMaxStackSize() - mevcut.getAmount());
                mevcut.setAmount(mevcut.getAmount() + eklenecek);
                kalan.setAmount(kalan.getAmount() - eklenecek);
            }
        }
        for (int i = 0; i < DEPO_SLOT && kalan.getAmount() > 0; i++) {
            if (inv.getItem(i) == null || inv.getItem(i).getType() == Material.AIR) {
                inv.setItem(i, kalan.clone());
                kalan.setAmount(0);
            }
        }
        return kalan.getAmount() > 0 ? kalan : null;
    }

    // ------------------------------------------------------------------ BEKLEYEN EŞYALAR
    public void bekleyenEkle(UUID oyuncu, ItemStack item) {
        bekleyenler.computeIfAbsent(oyuncu, k -> new ArrayList<>()).add(item);
    }

    public Map<UUID, List<ItemStack>> bekleyenler() { return bekleyenler; }

    public int bekleyenSayisi(UUID oyuncu) { return bekleyenler.getOrDefault(oyuncu, List.of()).size(); }

    /** /klan ganimet: bekleyen eşyalardan envantere sığanları verir, kalanı bekletir. */
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
        plugin.log().yaz(plugin.klanManager().oyuncununKlani(p.getUniqueId()), p.getName(), "BEKLEYEN_ALDI", verilen + " yığın, kalan " + kalan.size());
        if (kalan.isEmpty()) m().gonder(p, "bekleyen-alindi", "&aBekleyen tüm eşyalarınızı aldınız.");
        else m().gonder(p, "bekleyen-kismen", "&eEnvanteriniz doldu. {kalan} yığın eşya hâlâ bekliyor, yer açıp tekrar &6/klan ganimet&e yazın.", "kalan", kalan.size());
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
    }

    // ------------------------------------------------------------------ YETKİ KONTROLLÜ TIKLAMALAR
    private enum Erisim { KOYABILIR, LIMITLI_ALABILIR, SERBEST }

    private Erisim erisim(Klan a, KlanUyesi u) {
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

        Klan a = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (a == null || !a.id.equals(sahip.klanId)) { event.setCancelled(true); p.closeInventory(); return; }
        if (plugin.kasaKilitliMi(a)) {
            event.setCancelled(true);
            m().gonder(p, "kasa-kilitli", "&cSavaş sürerken klan kasası kilitlidir.");
            return;
        }
        KlanUyesi u = a.uyeler.get(p.getUniqueId());
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
            // "Aynı türleri topla" kasadan da çekebileceği için sadece lidere açık.
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

    private void logla(Klan a, Player p, String islem, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return;
        plugin.log().yaz(a, p.getName(), islem, item.getAmount() + "x " + item.getType().name());
    }

    /** Klandan çıkarılan oyuncu açık kasayı kullanmaya devam edemesin. */
    public void kapat(Player p) {
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof Sahip) p.closeInventory();
    }
}
