package me.klansistemi.buyu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import me.klansistemi.EsyaKasasi;
import me.klansistemi.KlanSistemi;
import me.klansistemi.Mesaj;
import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;

/**
 * Klan katmanı (kasa-bağlı) büyülü eşyaların kuralları.
 *
 * Eşya sadece klan kasasında durur; üyeler ödünç alıp kullanır (giyer, kazar, vurur) ama başka bir yere koyamaz,
 * atamaz, takas edemez. Ölünce, klandan ayrılınca ya da kayıt defteriyle uyuşmazsa kasaya döner.
 * Kayıt defteri doğruluk kaynağıdır: sahte/kopya eşya silinir; sadece kaydı geride kalmış eşya silinmez,
 * kaydı düzeltilir ya da kasaya iade edilir (sunucu çökmesinde masum eşya kaybolmasın).
 */
public class KasaBagliKurallar implements Listener {

    private final KlanSistemi plugin;
    // Ölünce kasaya dönmesi kapalıysa: oyuncuya yeniden doğunca geri verilecek eşyalar
    private final Map<UUID, List<ItemStack>> dogunceVerilecek = new java.util.HashMap<>();

    public KasaBagliKurallar(KlanSistemi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }
    private BuyuEsyasi esya() { return plugin.buyuEsyasi(); }
    private KayitDefteri defter() { return plugin.defter(); }

    private int oduncLimit() { return plugin.getConfig().getInt("buyu.kasa-bagli.odunc-limit", 3); }
    private boolean olunceKasayaDon() { return plugin.getConfig().getBoolean("buyu.kasa-bagli.olunce-kasaya-don", true); }

    private boolean bagli(ItemStack item) { return item != null && esya().kasaBagliMi(item); }

    // ------------------------------------------------------------------ KASA (EsyaKasasi'nden çağrılır)
    /**
     * Klan eşya kasasındaki tıklamalarda kasa-bağlı eşya varsa kuralları uygular.
     * @return true: tıklama burada işlendi (izin verildi ya da iptal edildi), normal kasa kuralları uygulanmaz
     */
    public boolean kasaTiklamasi(InventoryClickEvent event, Player p, Klan k, KlanUyesi u, boolean ustte) {
        ItemStack imlec = event.getCursor();
        ItemStack mevcut = event.getCurrentItem();
        ItemStack hotbar = event.getHotbarButton() >= 0 ? p.getInventory().getItem(event.getHotbarButton()) : null;
        if (!bagli(imlec) && !bagli(mevcut) && !bagli(hotbar)) return false;

        InventoryAction aksiyon = event.getAction();
        if (ustte) {
            switch (aksiyon) {
                // Kasaya geri koyma
                case PLACE_ALL, PLACE_ONE, PLACE_SOME -> {
                    if (!kasayaKonabilir(p, k, imlec)) { event.setCancelled(true); return true; }
                }
                // Kasadan ödünç alma
                case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, MOVE_TO_OTHER_INVENTORY -> {
                    if (!oduncAlinabilir(p, k, u, mevcut, aksiyon == InventoryAction.MOVE_TO_OTHER_INVENTORY)) { event.setCancelled(true); return true; }
                }
                default -> {
                    event.setCancelled(true);
                    m().gonder(p, "buyu-kasa-islem", "&cKlan eşyalarını kasadan sadece tıklayarak ya da Shift ile alıp koyabilirsiniz.");
                    return true;
                }
            }
        } else {
            // Oyuncunun kendi envanteri: Shift ile kasaya koyma
            if (aksiyon == InventoryAction.MOVE_TO_OTHER_INVENTORY && bagli(mevcut) && !kasayaKonabilir(p, k, mevcut)) {
                event.setCancelled(true);
                return true;
            }
        }
        // İşlem tamamlandıktan sonra kayıt defterini gerçek duruma göre güncelle
        Bukkit.getScheduler().runTask(plugin, () -> senkronize(p, k));
        return true;
    }

    private boolean kasayaKonabilir(Player p, Klan k, ItemStack item) {
        if (!bagli(item)) return true;
        KayitDefteri.Kayit kayit = defter().get(esya().kisaKod(item));
        if (kayit == null || !k.id.equals(kayit.sahipKlan) || kayit.durum == KayitDefteri.Durum.SILINDI) {
            m().gonder(p, "buyu-kasa-yabanci", "&cBu eşya klanınıza ait değil, kasaya konamaz.");
            return false;
        }
        return true;
    }

    private boolean oduncAlinabilir(Player p, Klan k, KlanUyesi u, ItemStack item, boolean shift) {
        if (!bagli(item)) return true;
        if (u.borclu) { m().gonder(p, "esya-borclu", "&cAidat borcunuz olduğu için kasadan eşya alamazsınız."); return false; }
        KayitDefteri.Kayit kayit = defter().get(esya().kisaKod(item));
        if (kayit == null || !k.id.equals(kayit.sahipKlan) || kayit.durum == KayitDefteri.Durum.SILINDI) {
            m().gonder(p, "buyu-gecersiz", "&cBu eşyanın kaydı geçersiz, kullanılamaz. Yetkililere bildirin.");
            return false;
        }
        int tasinan = tasinanSayisi(p);
        if (tasinan >= oduncLimit()) {
            m().gonder(p, "buyu-odunc-limit", "&cAynı anda en fazla {max} klan eşyası taşıyabilirsiniz.", "max", oduncLimit());
            return false;
        }
        if (shift && p.getInventory().firstEmpty() == -1) {
            m().gonder(p, "buyu-envanter-dolu", "&cEnvanterinizde yer yok.");
            return false;
        }
        return true;
    }

    /** Oyuncunun üzerindeki (envanter, zırh, sol el, imleç) kasa-bağlı eşya sayısı. */
    public int tasinanSayisi(Player p) {
        int n = 0;
        for (ItemStack item : p.getInventory().getContents()) if (bagli(item)) n++;
        if (bagli(p.getItemOnCursor())) n++;
        return n;
    }

    /** Kayıt defterini kasanın ve oyuncunun gerçek durumuna göre günceller (ödünç / kasada). */
    public void senkronize(Player p, Klan k) {
        Set<String> kasada = new HashSet<>();
        for (Inventory inv : plugin.esya().kasaSayfalari(k)) {
            for (int i = 0; i < EsyaKasasi.DEPO_SLOT; i++) {
                String kod = esya().kisaKod(inv.getItem(i));
                if (kod != null) kasada.add(kod);
            }
        }
        for (String kod : kasada) {
            KayitDefteri.Kayit kayit = defter().get(kod);
            if (kayit != null && k.id.equals(kayit.sahipKlan) && kayit.durum != KayitDefteri.Durum.KASADA && kayit.durum != KayitDefteri.Durum.SILINDI) {
                defter().durumAyarla(kayit, KayitDefteri.Durum.KASADA, null, "Kasaya kondu: " + p.getName());
                plugin.log().yaz(k, p.getName(), "BUYU_KASAYA_KOYDU", kod);
            }
        }
        if (!p.isOnline()) return;
        List<ItemStack> uzerinde = new ArrayList<>();
        for (ItemStack item : p.getInventory().getContents()) if (bagli(item)) uzerinde.add(item);
        if (bagli(p.getItemOnCursor())) uzerinde.add(p.getItemOnCursor());
        for (ItemStack item : uzerinde) {
            KayitDefteri.Kayit kayit = defter().get(esya().kisaKod(item));
            if (kayit == null || !k.id.equals(kayit.sahipKlan)) continue;
            if (kayit.durum != KayitDefteri.Durum.ODUNC || !p.getUniqueId().equals(kayit.oduncOyuncu)) {
                defter().durumAyarla(kayit, KayitDefteri.Durum.ODUNC, p.getUniqueId(), "Ödünç alındı");
                plugin.log().yaz(k, p.getName(), "BUYU_ODUNC_ALDI", kayit.kod);
                esya().loreYenile(item);
            }
        }
    }

    // ------------------------------------------------------------------ DOĞRULAMA (giriş, dakikalık, kasa açılışı)
    /**
     * Oyuncunun envanterindeki ve ender sandığındaki kasa-bağlı eşyaları kayıt defteriyle karşılaştırır.
     * Sahte/kopya/geçersiz olanları siler; başka klana ait ya da klandan ayrılmış oyuncudakileri kasaya iade eder.
     */
    public void oyuncuyuDogrula(Player p) {
        // Savaşta olmayan oyuncunun arena dengesi için düşürülmüş büyüleri geri yüklenir (çökme sonrası dahil)
        if (plugin.savas().katilimciSavasi(p.getUniqueId()) == null) plugin.buyuSavas().arenaSeviyeGeriYukle(p);
        Klan oyuncuKlani = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        Set<String> goruldu = new HashSet<>();
        boolean degisti = envanteriDogrula(p, p.getInventory(), oyuncuKlani, goruldu, true);
        // Ender sandığına konmuş olamaz; varsa (eski sürüm vb.) kasaya iade edilir
        degisti |= envanteriDogrula(p, p.getEnderChest(), oyuncuKlani, goruldu, false);
        if (degisti) p.updateInventory();
    }

    private boolean envanteriDogrula(Player p, Inventory inv, Klan oyuncuKlani, Set<String> goruldu, boolean kullanilabilir) {
        boolean degisti = false;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (!bagli(item)) continue;
            String kod = esya().kisaKod(item);
            KayitDefteri.Kayit kayit = defter().get(kod);
            String sonuc = karar(p, item, kayit, oyuncuKlani, goruldu, kullanilabilir);
            if (sonuc == null) { esya().loreYenile(item); continue; }
            inv.setItem(i, null);
            degisti = true;
            if (sonuc.equals("IADE")) {
                iadeEt(kayit, item, p.getName() + " üzerinden iade");
                m().gonder(p, "buyu-iade", "&e{kod} kimlikli klan eşyası sahibi klanın kasasına iade edildi.", "kod", kod);
            } else {
                plugin.log().yaz(oyuncuKlani, p.getName(), "BUYU_SILINDI", kod + " | " + sonuc);
                m().gonder(p, "buyu-silindi", "&c{kod} kimlikli eşya geçersiz olduğu için kaldırıldı. &7({sebep})", "kod", kod, "sebep", sonuc);
            }
        }
        return degisti;
    }

    /** null: eşya oyuncuda kalabilir, "IADE": sahibi kasaya döner, başka metin: silme sebebi. */
    private String karar(Player p, ItemStack item, KayitDefteri.Kayit kayit, Klan oyuncuKlani, Set<String> goruldu, boolean kullanilabilir) {
        if (kayit == null || !kayit.uuid.toString().equals(esya().uuidMetni(item))) return "Kayıtsız / sahte";
        if (kayit.durum == KayitDefteri.Durum.SILINDI) return "Kayıt silinmiş: " + (kayit.not != null ? kayit.not : "-");
        if (!goruldu.add(kayit.kod)) return "Kopya (aynı kimlik iki kez)";
        Klan sahip = plugin.klanManager().klanGetir(kayit.sahipKlan);
        if (sahip == null) {
            defter().durumAyarla(kayit, KayitDefteri.Durum.SILINDI, null, "Sahip klan yok");
            return "Sahip klan yok";
        }
        if (!kullanilabilir || oyuncuKlani == null || !oyuncuKlani.id.equals(sahip.id)) return "IADE";
        if (kayit.durum == KayitDefteri.Durum.ODUNC) {
            if (p.getUniqueId().equals(kayit.oduncOyuncu)) return null;
            return "Kopya (kayıtta başka oyuncuda)";
        }
        // Kayıt KASADA diyor: kasada gerçekten varsa bu bir kopyadır; yoksa kayıt geride kalmıştır (çökme vb.)
        if (plugin.esya().kasadaVarMi(sahip, kayit.kod)) return "Kopya (kasada da var)";
        defter().durumAyarla(kayit, KayitDefteri.Durum.ODUNC, p.getUniqueId(), "Kayıt düzeltildi (oyuncuda bulundu)");
        plugin.log().yaz(sahip, p.getName(), "BUYU_KAYIT_DUZELTILDI", kayit.kod + " -> ODUNC");
        return null;
    }

    /** Eşyayı kayıttaki sahip klanın kasasına koyar; kasa doluysa liderin bekleyen eşyalarına yazar. */
    public void iadeEt(KayitDefteri.Kayit kayit, ItemStack item, String sebep) {
        Klan sahip = plugin.klanManager().klanGetir(kayit.sahipKlan);
        if (sahip == null) {
            defter().durumAyarla(kayit, KayitDefteri.Durum.SILINDI, null, "Sahip klan yok");
            return;
        }
        esya().loreYenile(item);
        ItemStack kalan = plugin.esya().kasayaKoy(sahip, item);
        if (kalan == null) {
            defter().durumAyarla(kayit, KayitDefteri.Durum.KASADA, null, sebep);
        } else {
            KlanUyesi lider = sahip.lider();
            plugin.esya().bekleyenEkle(lider.uuid, kalan);
            defter().durumAyarla(kayit, KayitDefteri.Durum.ODUNC, lider.uuid, sebep + " (kasa dolu, liderin bekleyen eşyalarına)");
        }
        plugin.log().yaz(sahip, "-", "BUYU_IADE", kayit.kod + " | " + sebep);
    }

    /** Kasadaki kasa-bağlı eşyaları denetler: kopyaları, geçersizleri ve başka klana ait olanları ayıklar. */
    public void kasayiDogrula(Klan k) {
        Set<String> goruldu = new HashSet<>();
        for (Inventory inv : plugin.esya().kasaSayfalari(k)) {
            for (int i = 0; i < EsyaKasasi.DEPO_SLOT; i++) {
                ItemStack item = inv.getItem(i);
                if (!bagli(item)) continue;
                String kod = esya().kisaKod(item);
                KayitDefteri.Kayit kayit = defter().get(kod);
                String sebep = null;
                if (kayit == null || !kayit.uuid.toString().equals(esya().uuidMetni(item))) sebep = "Kayıtsız / sahte";
                else if (kayit.durum == KayitDefteri.Durum.SILINDI) sebep = "Kayıt silinmiş";
                else if (!goruldu.add(kod)) sebep = "Kopya";
                if (sebep != null) {
                    inv.setItem(i, null);
                    plugin.log().yaz(k, "-", "BUYU_SILINDI", kod + " | kasada | " + sebep);
                    continue;
                }
                if (!k.id.equals(kayit.sahipKlan)) {
                    inv.setItem(i, null);
                    iadeEt(kayit, item, "Başka klanın kasasında bulundu");
                    continue;
                }
                if (kayit.durum != KayitDefteri.Durum.KASADA) defter().durumAyarla(kayit, KayitDefteri.Durum.KASADA, null, "Kasada bulundu");
                plugin.buyuSavas().arenaSeviyesiniGeriAl(item); // Arenadan kalmış düşük seviye varsa düzelt
                esya().loreYenile(item);
            }
        }
    }

    /** Klandan ayrılan/atılan oyuncu çevrimiçiyse üzerindeki klan eşyaları hemen kasaya döner (çevrimdışıysa girişte). */
    public void uyeAyrildi(UUID oyuncu) {
        Player p = Bukkit.getPlayer(oyuncu);
        if (p != null) Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) oyuncuyuDogrula(p); });
    }

    /** Dakikada bir: çevrimiçi oyuncular denetlenir. */
    public void periyodikKontrol() {
        for (Player p : Bukkit.getOnlinePlayers()) oyuncuyuDogrula(p);
    }

    // ------------------------------------------------------------------ TAŞIMA YASAKLARI
    /** Kasa-bağlı eşyanın konabileceği üst envanterler: oyuncunun kendi envanteri, örs ve demirci masası. */
    private boolean izinliUstEnvanter(Inventory ust) {
        InventoryType t = ust.getType();
        return t == InventoryType.CRAFTING || t == InventoryType.ANVIL || t == InventoryType.SMITHING;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        Inventory ust = event.getView().getTopInventory();
        if (ust.getHolder() instanceof EsyaKasasi.Sahip) return; // Kasa kendi kurallarıyla işlenir
        ItemStack imlec = event.getCursor(), mevcut = event.getCurrentItem();
        ItemStack hotbar = event.getHotbarButton() >= 0 ? p.getInventory().getItem(event.getHotbarButton()) : null;
        ItemStack solEl = event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND ? p.getInventory().getItemInOffHand() : null;
        if (!bagli(imlec) && !bagli(mevcut) && !bagli(hotbar) && !bagli(solEl)) return;

        // Bundle'a koyma / bundle ile alma
        if ((bagli(imlec) && mevcut != null && mevcut.getType().name().endsWith("BUNDLE"))
                || (bagli(mevcut) && imlec != null && imlec.getType().name().endsWith("BUNDLE"))) {
            iptal(event, p);
            return;
        }
        int ustBoyut = ust.getSize();
        boolean ustSlot = event.getRawSlot() >= 0 && event.getRawSlot() < ustBoyut;

        // Crafting görünümünde 2x2 ızgaraya koymak da yasak (tamir tarifi büyüleri siler)
        boolean ustYasak = !izinliUstEnvanter(ust) || (ust.getType() == InventoryType.CRAFTING && ustSlot && event.getRawSlot() <= 4);
        if (!ustYasak) return;

        InventoryAction a = event.getAction();
        boolean ustaKoyma =
                (ustSlot && bagli(imlec) && (a == InventoryAction.PLACE_ALL || a == InventoryAction.PLACE_ONE || a == InventoryAction.PLACE_SOME || a == InventoryAction.SWAP_WITH_CURSOR))
                || (!ustSlot && bagli(mevcut) && a == InventoryAction.MOVE_TO_OTHER_INVENTORY && ust.getType() != InventoryType.CRAFTING)
                || (ustSlot && (bagli(hotbar) || bagli(solEl)) && (a == InventoryAction.HOTBAR_SWAP || a == InventoryAction.HOTBAR_MOVE_AND_READD || a == InventoryAction.SWAP_WITH_CURSOR || event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND));
        if (ustaKoyma) iptal(event, p);
    }

    private void iptal(InventoryClickEvent event, Player p) {
        event.setCancelled(true);
        m().gonder(p, "buyu-tasima-yasak", "&cKlan eşyaları sadece klan kasasında saklanabilir, başka bir yere konamaz.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!bagli(event.getOldCursor())) return;
        Inventory ust = event.getView().getTopInventory();
        if (ust.getHolder() instanceof EsyaKasasi.Sahip) return;
        for (int slot : event.getRawSlots()) {
            if (slot < ust.getSize() && (!izinliUstEnvanter(ust) || (ust.getType() == InventoryType.CRAFTING && slot <= 4))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent event) {
        // Creative'de orta tık / envanter ile kopyalama engellenir
        if (bagli(event.getCursor()) || bagli(event.getCurrentItem())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player p) m().gonder(p, "buyu-creative", "&cKlan eşyaları yaratıcı modda kullanılamaz.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!bagli(event.getItemDrop().getItemStack())) return;
        event.setCancelled(true);
        m().gonder(event.getPlayer(), "buyu-atilamaz", "&cKlan eşyaları yere atılamaz. Kasaya geri koyun.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame) && !(event.getRightClicked() instanceof Allay)) return;
        Player p = event.getPlayer();
        if (bagli(p.getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
            m().gonder(p, "buyu-tasima-yasak", "&cKlan eşyaları sadece klan kasasında saklanabilir, başka bir yere konamaz.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (bagli(event.getPlayerItem())) {
            event.setCancelled(true);
            m().gonder(event.getPlayer(), "buyu-tasima-yasak", "&cKlan eşyaları sadece klan kasasında saklanabilir, başka bir yere konamaz.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPot(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.DECORATED_POT) return;
        if (bagli(event.getItem())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ ÖLÜM / KIRILMA
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) return; // Eşyalar zaten oyuncuda kalıyor (örn. arena)
        Player p = event.getPlayer();
        List<ItemStack> donecek = new ArrayList<>();
        event.getDrops().removeIf(item -> {
            if (!bagli(item)) return false;
            donecek.add(item);
            return true;
        });
        if (donecek.isEmpty()) return;
        if (!olunceKasayaDon()) {
            dogunceVerilecek.computeIfAbsent(p.getUniqueId(), x -> new ArrayList<>()).addAll(donecek);
            return;
        }
        for (ItemStack item : donecek) {
            KayitDefteri.Kayit kayit = defter().get(esya().kisaKod(item));
            if (kayit == null || kayit.durum == KayitDefteri.Durum.SILINDI) continue;
            iadeEt(kayit, item, p.getName() + " öldü");
        }
        m().gonder(p, "buyu-olum-iade", "&e{sayi} klan eşyanız öldüğünüz için klan kasasına geri döndü.", "sayi", donecek.size());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        List<ItemStack> liste = dogunceVerilecek.remove(event.getPlayer().getUniqueId());
        if (liste == null) return;
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (ItemStack item : liste) {
                for (ItemStack sigmayan : p.getInventory().addItem(item).values()) {
                    KayitDefteri.Kayit kayit = defter().get(esya().kisaKod(sigmayan));
                    if (kayit != null) iadeEt(kayit, sigmayan, "Envanter dolu");
                }
            }
        });
    }

    @EventHandler
    public void onBreak(PlayerItemBreakEvent event) {
        ItemStack item = event.getBrokenItem();
        if (!esya().buyuluMu(item)) return;
        KayitDefteri.Kayit kayit = defter().get(esya().kisaKod(item));
        if (kayit == null) return;
        defter().durumAyarla(kayit, KayitDefteri.Durum.SILINDI, null, "Kırıldı (" + event.getPlayer().getName() + ")");
        plugin.log().yaz(plugin.klanManager().klanGetir(kayit.sahipKlan), event.getPlayer().getName(), "BUYU_KIRILDI", kayit.kod);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) oyuncuyuDogrula(p); }, 40L);
    }

    // ------------------------------------------------------------------ BİRLEŞTİRME YASAKLARI (her iki katman)
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent event) {
        ItemStack ilk = event.getInventory().getItem(0), ikinci = event.getInventory().getItem(1);
        boolean ilkBuyulu = esya().buyuluMu(ilk), ikinciBuyulu = esya().buyuluMu(ikinci);
        if (!ilkBuyulu && !ikinciBuyulu) return;
        // İkinci slot: sadece boş (isim verme) ya da tamir malzemesi olabilir; kitap/alet/büyülü eşya olamaz
        if (ikinciBuyulu || (ikinci != null && (ikinci.getType() == Material.ENCHANTED_BOOK || ikinci.getType() == ilk.getType()
                || !ikinci.getEnchantments().isEmpty()))) {
            event.setResult(null);
            return;
        }
        ItemStack sonuc = event.getResult();
        if (sonuc == null || sonuc.getType() == Material.AIR) return;
        // Sonuç eşyası ilk eşyanın özel büyülerini ve kimliğini aynen korumalı
        ItemMeta sMeta = sonuc.getItemMeta();
        for (Enchantment e : new ArrayList<>(sMeta.getEnchants().keySet())) sMeta.removeEnchant(e);
        for (Map.Entry<Enchantment, Integer> e : ilk.getEnchantments().entrySet()) sMeta.addEnchant(e.getKey(), e.getValue(), true);
        ItemMeta iMeta = ilk.getItemMeta();
        for (org.bukkit.NamespacedKey key : iMeta.getPersistentDataContainer().getKeys()) {
            if (!key.getNamespace().equals(plugin.getName().toLowerCase(java.util.Locale.ROOT))) continue;
            copyPdc(iMeta, sMeta, key);
        }
        sonuc.setItemMeta(sMeta);
        esya().loreYenile(sonuc);
        event.setResult(sonuc);
    }

    private static void copyPdc(ItemMeta kaynak, ItemMeta hedef, org.bukkit.NamespacedKey key) {
        var k = kaynak.getPersistentDataContainer();
        var h = hedef.getPersistentDataContainer();
        if (k.has(key, org.bukkit.persistence.PersistentDataType.STRING)) h.set(key, org.bukkit.persistence.PersistentDataType.STRING, k.get(key, org.bukkit.persistence.PersistentDataType.STRING));
        else if (k.has(key, org.bukkit.persistence.PersistentDataType.LONG)) h.set(key, org.bukkit.persistence.PersistentDataType.LONG, k.get(key, org.bukkit.persistence.PersistentDataType.LONG));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGrindstone(PrepareGrindstoneEvent event) {
        if (esya().buyuluMu(event.getInventory().getItem(0)) || esya().buyuluMu(event.getInventory().getItem(1))) event.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEnchant(PrepareItemEnchantEvent event) {
        if (esya().buyuluMu(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (esya().buyuluMu(item)) { event.getInventory().setResult(null); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSmithing(PrepareSmithingEvent event) {
        // Yükseltme (elmas -> netherit) serbest: kimlik ve büyüler korunur, lore yenilenir
        ItemStack sonuc = event.getResult();
        if (sonuc != null && esya().buyuluMu(sonuc)) esya().loreYenile(sonuc);
    }

}
