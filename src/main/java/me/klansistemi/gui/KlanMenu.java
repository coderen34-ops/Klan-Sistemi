package me.klansistemi.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import me.klansistemi.AidatManager;
import me.klansistemi.KlanSistemi;
import me.klansistemi.Mesaj;
import me.klansistemi.Para;
import me.klansistemi.Zaman;
import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;
import me.klansistemi.model.Rol;

/**
 * Klan menüleri. Menüler başlık yazısıyla değil kendi InventoryHolder tipiyle tanınır;
 * böylece aynı isimli bir sandık ya da başka eklentinin menüsüyle karışmaz.
 */
public class KlanMenu implements Listener {

    public enum Tip { ANA, UYELER, KASA, DAVET, ATMA_ONAY }

    /** Menüyü tanımlayan sahip: hangi menü, hangi klan. */
    public static class Sahip implements InventoryHolder {
        public final Tip tip;
        public final UUID klanId;
        /** Atma onay menüsünde atılacak üye. */
        public final UUID hedef;
        /** Üyeler ve Davet menülerinde slot -> oyuncu eşlemesi (kafaya tıklayınca kimin olduğunu bilmek için). */
        public final Map<Integer, UUID> slotOyuncu = new HashMap<>();
        private Inventory envanter;

        Sahip(Tip tip, UUID klanId, UUID hedef) {
            this.tip = tip;
            this.klanId = klanId;
            this.hedef = hedef;
        }

        @Override
        public Inventory getInventory() { return envanter; }
    }

    private static final int SLOT_BILGI = 4, SLOT_UYELER = 10, SLOT_KASA = 12, SLOT_AIDAT = 14, SLOT_SOHBET = 16, SLOT_YARDIM = 22;
    private static final int SLOT_ILISKILER = 20, SLOT_DIPLOMASI = 24, SLOT_ESYA = 31, SLOT_SAVAS = 18;
    private static final int SLOT_GERI = 49, SLOT_DAVET = 47;
    private static final int SLOT_ONAY = 11, SLOT_VAZGEC = 15, SLOT_HEDEF = 13;

    private final KlanSistemi plugin;

    public KlanMenu(KlanSistemi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }

    private Inventory olustur(Tip tip, Klan a, int boyut, String baslik) {
        return olustur(tip, a, boyut, baslik, null);
    }

    private Inventory olustur(Tip tip, Klan a, int boyut, String baslik, UUID hedef) {
        Sahip sahip = new Sahip(tip, a.id, hedef);
        Inventory inv = Bukkit.createInventory(sahip, boyut, baslik);
        sahip.envanter = inv;
        return inv;
    }

    /** Atma yetkisi: Lider herkesi (kendisi hariç), Yardımcı sadece Üyeleri atabilir (KlanManager.at ile aynı kural). */
    private static boolean atabilir(KlanUyesi ben, KlanUyesi hedef) {
        if (ben.uuid.equals(hedef.uuid)) return false;
        return ben.rol == Rol.LIDER || (ben.rol == Rol.YARDIMCI && hedef.rol == Rol.UYE);
    }

    /**
     * Kafa (skull) meta'sına oyuncu profilini yazar. Oyuncu çevrimiçiyse zaten çözülmüş
     * (texture'lı) profilini kullanır; bu, istemcinin "Dynamic" (henüz çözülmemiş/canlı
     * sorgulanan skin) uyarısı göstermesini önler. Çevrimdışıysa isim+UUID ile devam eder,
     * bu durumda istemci skin'i kendi çözer ve "Dynamic" uyarısı yine görünebilir.
     */
    private static void kafaProfiliUygula(SkullMeta meta, UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            meta.setPlayerProfile(online.getPlayerProfile());
        } else {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
        }
    }

    private static ItemStack esya(Material mat, String ad, List<String> aciklama) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ad);
        meta.setLore(aciklama);
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    private static void doldur(Inventory inv) {
        ItemStack cam = esya(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, cam);
    }

    // ------------------------------------------------------------------ ANA MENÜ
    public void anaMenu(Player p) {
        Klan a = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (a == null) return;
        KlanUyesi ben = a.uyeler.get(p.getUniqueId());
        AidatManager aidat = plugin.aidat();
        long simdi = System.currentTimeMillis();

        Inventory inv = olustur(Tip.ANA, a, 27, m().metin("menu-ana-baslik", "&6&l{klan} &8| &7Klan Menüsü", "klan", a.isim));

        KlanUyesi lider = a.lider();
        List<String> bilgi = new ArrayList<>();
        bilgi.add(ChatColor.GRAY + "Lider: " + ChatColor.GOLD + (lider != null ? lider.isim : "-"));
        bilgi.add(ChatColor.GRAY + "Kuruluş: " + ChatColor.WHITE + Zaman.tarih(a.kurulus));
        bilgi.add(ChatColor.GRAY + "Üyeler: " + ChatColor.WHITE + a.uyeler.size() + "/" + plugin.ayar().maxUye());
        bilgi.add(ChatColor.GRAY + "Kasa: " + ChatColor.GREEN + Para.yaz(a.kasa));
        bilgi.add(ChatColor.GRAY + "Uzmanlık: " + plugin.uzmanlik().alanlarMetni(a));
        bilgi.add(ChatColor.GRAY + "Prestij: " + ChatColor.GREEN + a.prestij + ChatColor.GRAY + " (" + plugin.prestij().sira(a) + ". sırada)");
        bilgi.add(ChatColor.GRAY + "Rolünüz: " + ben.rol.renkliAd());
        inv.setItem(SLOT_BILGI, esya(Material.WHITE_BANNER, ChatColor.GOLD + "" + ChatColor.BOLD + a.isim, bilgi));

        inv.setItem(SLOT_UYELER, esya(Material.PLAYER_HEAD, ChatColor.YELLOW + "" + ChatColor.BOLD + "Üyeler",
                List.of(ChatColor.GRAY + "Klan üyelerini ve rollerini gör.",
                        ben.rol == Rol.LIDER ? ChatColor.AQUA + "Aidat durumları da görünür." : "",
                        "", ChatColor.YELLOW + "► Açmak için tıkla")));

        inv.setItem(SLOT_KASA, esya(Material.GOLD_INGOT, ChatColor.GOLD + "" + ChatColor.BOLD + "Klan Kasası",
                List.of(ChatColor.GRAY + "Bakiye: " + ChatColor.GREEN + Para.yaz(a.kasa),
                        ChatColor.GRAY + "Toplam aidat geliri: " + ChatColor.GREEN + Para.yaz(a.toplamAidat),
                        "", ChatColor.YELLOW + "► Açmak için tıkla")));

        AidatManager.Durum durum = aidat.durum(a, ben, simdi);
        List<String> aidatAciklama = new ArrayList<>();
        aidatAciklama.add(ChatColor.GRAY + "Dönem aidatı: " + ChatColor.YELLOW + Para.yaz(a.aidatMiktari));
        aidatAciklama.add(ChatColor.GRAY + "Durumunuz: " + durum.etiket);
        Integer hedef = aidat.odenecekDonem(a, ben, simdi);
        if (hedef != null) {
            long son = aidat.sonOdemeAni(a, ben, hedef, simdi);
            aidatAciklama.add(ChatColor.GRAY + "Son ödeme: " + ChatColor.WHITE + Zaman.tarih(son) + ChatColor.GRAY + " (" + Zaman.sure(son - simdi) + ")");
            aidatAciklama.add("");
            aidatAciklama.add(ChatColor.YELLOW + "► Ödemek için tıkla (bankadan)");
        }
        inv.setItem(SLOT_AIDAT, esya(Material.CLOCK, ChatColor.AQUA + "" + ChatColor.BOLD + "Aidat", aidatAciklama));

        boolean sohbetAcik = plugin.klanManager().sohbetAcikMi(p.getUniqueId());
        inv.setItem(SLOT_SOHBET, esya(sohbetAcik ? Material.WRITABLE_BOOK : Material.BOOK,
                ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Klan Sohbeti",
                List.of(ChatColor.GRAY + "Durum: " + (sohbetAcik ? ChatColor.GREEN + "AÇIK" : ChatColor.RED + "KAPALI"),
                        ChatColor.GRAY + "Tek mesaj için: " + ChatColor.WHITE + "/kc <mesaj>",
                        "", ChatColor.YELLOW + "► Açmak/kapatmak için tıkla")));

        int[] iliskiOzeti = plugin.iliski().ozet(a.id);
        inv.setItem(SLOT_ILISKILER, esya(Material.WHITE_BANNER, ChatColor.WHITE + "" + ChatColor.BOLD + "İlişkiler",
                List.of(ChatColor.GREEN + "Dost: " + iliskiOzeti[0],
                        ChatColor.RED + "Husumet: " + iliskiOzeti[1],
                        ChatColor.GRAY + "Diğerleri: Tarafsız",
                        "", ChatColor.GRAY + "Ayrıntı: " + ChatColor.WHITE + "/klan iliski liste")));
        List<String> savasAciklama = new ArrayList<>(plugin.savas().durumSatirlari(a));
        if (plugin.savas().hazirliktaMi(a.id)) { savasAciklama.add(""); savasAciklama.add(ChatColor.YELLOW + "► Savaşa katılmak için tıkla"); }
        inv.setItem(SLOT_SAVAS, esya(Material.IRON_SWORD, ChatColor.RED + "" + ChatColor.BOLD + "Savaş Durumu", savasAciklama));

        if (ben.rol == Rol.LIDER) {
            inv.setItem(SLOT_DIPLOMASI, esya(Material.RED_BANNER, ChatColor.DARK_RED + "" + ChatColor.BOLD + "Diplomasi Paneli",
                    List.of(ChatColor.GRAY + "Sadece Lider görebilir.",
                            ChatColor.GRAY + "Diğer klanlar hakkında görüş yaz,",
                            ChatColor.GRAY + "ittifak kur ya da husumet ilan et.",
                            "", ChatColor.YELLOW + "► Açmak için tıkla")));
        }

        inv.setItem(SLOT_YARDIM, esya(Material.KNOWLEDGE_BOOK, ChatColor.GREEN + "" + ChatColor.BOLD + "Komutlar",
                List.of(ChatColor.GRAY + "Tüm klan komutlarını gör.", "", ChatColor.YELLOW + "► Tıkla")));

        doldur(inv);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
    }

    // ------------------------------------------------------------------ ÜYELER
    public void uyelerMenu(Player p) {
        Klan a = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (a == null) return;
        KlanUyesi ben = a.uyeler.get(p.getUniqueId());
        boolean liderMi = ben.rol == Rol.LIDER;
        AidatManager aidat = plugin.aidat();
        long simdi = System.currentTimeMillis();

        Inventory inv = olustur(Tip.UYELER, a, 54, m().metin("menu-uyeler-baslik", "&6&l{klan} &8| &7Üyeler", "klan", a.isim));
        Sahip sahip = (Sahip) inv.getHolder();
        int slot = 0;
        for (KlanUyesi u : a.siraliUyeler()) {
            if (slot >= 45) break;
            ItemStack kafa = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) kafa.getItemMeta();
            kafaProfiliUygula(meta, u.uuid);
            boolean cevrimici = Bukkit.getPlayer(u.uuid) != null;
            meta.setDisplayName(u.rol.renk + "" + ChatColor.BOLD + u.isim + (cevrimici ? ChatColor.GREEN + " ●" : ChatColor.DARK_GRAY + " ○"));
            List<String> aciklama = new ArrayList<>();
            aciklama.add(ChatColor.GRAY + "Rol: " + u.rol.renkliAd() + (u.rol == Rol.YARDIMCI ? ChatColor.GRAY + " (Kıdem " + u.kidem + ")" : ""));
            aciklama.add(ChatColor.GRAY + "Katılma: " + ChatColor.WHITE + Zaman.tarih(u.katilma));
            if (liderMi) {
                aciklama.add("");
                aciklama.add(ChatColor.GRAY + "Aidat: " + aidat.durum(a, u, simdi).etiket);
                aciklama.add(ChatColor.GRAY + "Son ödeme: " + ChatColor.WHITE + (u.sonOdeme > 0 ? Zaman.tarih(u.sonOdeme) : "-"));
                if (u.ardisikOdenmeyen > 0) aciklama.add(ChatColor.RED + "Üst üste ödenmeyen: " + u.ardisikOdenmeyen);
            }
            if (atabilir(ben, u)) {
                aciklama.add("");
                aciklama.add(ChatColor.RED + "► Klandan atmak için tıkla");
            }
            meta.setLore(aciklama);
            kafa.setItemMeta(meta);
            sahip.slotOyuncu.put(slot, u.uuid);
            inv.setItem(slot++, kafa);
        }
        if (ben.rol != Rol.UYE) {
            inv.setItem(SLOT_DAVET, esya(Material.LIME_DYE, ChatColor.GREEN + "" + ChatColor.BOLD + "Oyuncu Davet Et",
                    List.of(ChatColor.GRAY + "Klanı olmayan çevrimiçi oyuncuları listeler.",
                            ChatColor.GRAY + "Seçtiğine davet gider, kabul ederse katılır.",
                            "", ChatColor.YELLOW + "► Açmak için tıkla")));
        }
        inv.setItem(SLOT_GERI, esya(Material.ARROW, ChatColor.YELLOW + "Geri", List.of()));
        doldur(inv);
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ DAVET
    public void davetMenu(Player p) {
        Klan a = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (a == null) return;
        KlanUyesi ben = a.uyeler.get(p.getUniqueId());
        if (ben.rol == Rol.UYE) { m().gonder(p, "yetki-yok", "&cBu işlem için yetkiniz yok."); return; }

        Inventory inv = olustur(Tip.DAVET, a, 54, m().metin("menu-davet-baslik", "&6&l{klan} &8| &7Davet Et", "klan", a.isim));
        Sahip sahip = (Sahip) inv.getHolder();
        int slot = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (slot >= 45) break;
            if (o.getUniqueId().equals(p.getUniqueId())) continue;
            if (plugin.klanManager().oyuncununKlani(o.getUniqueId()) != null) continue;
            ItemStack kafa = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) kafa.getItemMeta();
            kafaProfiliUygula(meta, o.getUniqueId());
            meta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + o.getName());
            meta.setLore(List.of(ChatColor.GRAY + "Klanı yok.", "", ChatColor.GREEN + "► Davet göndermek için tıkla"));
            kafa.setItemMeta(meta);
            sahip.slotOyuncu.put(slot, o.getUniqueId());
            inv.setItem(slot++, kafa);
        }
        if (slot == 0) {
            inv.setItem(22, esya(Material.BARRIER, ChatColor.RED + "Davet edilebilecek oyuncu yok",
                    List.of(ChatColor.GRAY + "Çevrimiçi ve klansız kimse bulunmuyor.")));
        }
        inv.setItem(SLOT_GERI, esya(Material.ARROW, ChatColor.YELLOW + "Geri", List.of()));
        doldur(inv);
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ ATMA ONAYI
    public void atmaOnayMenu(Player p, KlanUyesi hedef) {
        Klan a = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (a == null) return;
        Inventory inv = olustur(Tip.ATMA_ONAY, a, 27, m().metin("menu-atma-baslik", "&6&l{klan} &8| &cAtma Onayı", "klan", a.isim), hedef.uuid);
        ItemStack kafa = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) kafa.getItemMeta();
        kafaProfiliUygula(meta, hedef.uuid);
        meta.setDisplayName(hedef.rol.renk + "" + ChatColor.BOLD + hedef.isim);
        meta.setLore(List.of(ChatColor.GRAY + "Rol: " + hedef.rol.renkliAd(), "", ChatColor.RED + "Bu oyuncu klandan atılacak."));
        kafa.setItemMeta(meta);
        inv.setItem(SLOT_HEDEF, kafa);
        inv.setItem(SLOT_ONAY, esya(Material.RED_CONCRETE, ChatColor.RED + "" + ChatColor.BOLD + "Evet, klandan at",
                List.of(ChatColor.GRAY + hedef.isim + " klandan çıkarılır.", ChatColor.GRAY + "Bu işlem geri alınamaz.")));
        inv.setItem(SLOT_VAZGEC, esya(Material.LIME_CONCRETE, ChatColor.GREEN + "" + ChatColor.BOLD + "Vazgeç", List.of()));
        doldur(inv);
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ KASA
    public void kasaMenu(Player p) {
        Klan a = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (a == null) return;
        KlanUyesi ben = a.uyeler.get(p.getUniqueId());
        int[] ist = plugin.aidat().istatistik(a);

        Inventory inv = olustur(Tip.KASA, a, 54, m().metin("menu-kasa-baslik", "&6&l{klan} &8| &7Kasa", "klan", a.isim));
        inv.setItem(13, esya(Material.GOLD_BLOCK, ChatColor.GOLD + "" + ChatColor.BOLD + "Kasa Bakiyesi",
                List.of(ChatColor.GREEN + Para.yaz(a.kasa),
                        ChatColor.GRAY + "Üst sınır: " + ChatColor.WHITE + Para.yaz(plugin.ayar().kasaUstSinir()),
                        "",
                        ChatColor.GRAY + "Yatırmak: " + ChatColor.WHITE + "/klan yatir <miktar>",
                        ben.rol == Rol.UYE ? ChatColor.DARK_GRAY + "Üyeler para çekemez." : ChatColor.GRAY + "Çekmek: " + ChatColor.WHITE + "/klan cek <miktar>",
                        ben.rol == Rol.YARDIMCI ? ChatColor.GRAY + "Günlük çekim limitiniz: " + ChatColor.WHITE + Para.yaz(plugin.ayar().yardimciGunlukLimit()) : "")));

        inv.setItem(29, esya(Material.PAPER, ChatColor.AQUA + "" + ChatColor.BOLD + "Aidat İstatistiği",
                List.of(ChatColor.GRAY + "Dönem aidatı: " + ChatColor.YELLOW + Para.yaz(a.aidatMiktari),
                        ChatColor.GRAY + "Toplam aidat geliri: " + ChatColor.GREEN + Para.yaz(a.toplamAidat),
                        "",
                        ChatColor.GREEN + "Ödedi: " + ist[0],
                        ChatColor.YELLOW + "Bekliyor: " + ist[1],
                        ChatColor.RED + "Borçlu: " + ist[2],
                        ChatColor.AQUA + "Muaf: " + ist[3])));

        inv.setItem(SLOT_ESYA, esya(Material.CHEST, ChatColor.GOLD + "" + ChatColor.BOLD + "Eşya Kasası",
                List.of(ChatColor.GRAY + "Klanın ortak eşya deposu.",
                        ben.rol == Rol.UYE ? ChatColor.DARK_GRAY + "Üyeler sadece eşya koyabilir." : ChatColor.GRAY + "Koyabilir ve alabilirsiniz.",
                        "", ChatColor.YELLOW + "► Açmak için tıkla")));

        inv.setItem(33, esya(Material.EMERALD, ChatColor.GREEN + "" + ChatColor.BOLD + "Aidat Öde",
                List.of(ChatColor.GRAY + "Bu dönemin aidatını bankanızdan öder.",
                        ChatColor.GRAY + "Durumunuz: " + plugin.aidat().durum(a, ben, System.currentTimeMillis()).etiket,
                        "", ChatColor.YELLOW + "► Ödemek için tıkla")));

        inv.setItem(SLOT_GERI, esya(Material.ARROW, ChatColor.YELLOW + "Geri", List.of()));
        doldur(inv);
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ TIKLAMALAR
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Sahip sahip)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;

        // Menü açıkken klandan çıkarıldıysa ya da klan dağıldıysa işlem yapılmaz
        Klan a = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (a == null || !a.id.equals(sahip.klanId)) { p.closeInventory(); return; }

        int slot = event.getRawSlot();
        switch (sahip.tip) {
            case ANA -> {
                if (slot == SLOT_UYELER) uyelerMenu(p);
                else if (slot == SLOT_KASA) kasaMenu(p);
                else if (slot == SLOT_AIDAT) { plugin.aidat().ode(p); anaMenu(p); }
                else if (slot == SLOT_SOHBET) { plugin.klanManager().sohbetDegistir(p); anaMenu(p); }
                else if (slot == SLOT_YARDIM) { p.closeInventory(); p.performCommand("klan yardim"); }
                else if (slot == SLOT_SAVAS && plugin.savas().hazirliktaMi(a.id)) { plugin.savas().katil(p); anaMenu(p); }
                else if (slot == SLOT_DIPLOMASI) plugin.panel().ac(p, me.klansistemi.gui.DiplomasiPanel.Sekme.KLANLAR, 0);
            }
            case UYELER -> {
                if (slot == SLOT_GERI) anaMenu(p);
                else if (slot == SLOT_DAVET) davetMenu(p);
                else {
                    UUID uuid = sahip.slotOyuncu.get(slot);
                    KlanUyesi ben = a.uyeler.get(p.getUniqueId());
                    KlanUyesi hedef = uuid == null ? null : a.uyeler.get(uuid);
                    if (hedef != null && atabilir(ben, hedef)) atmaOnayMenu(p, hedef);
                }
            }
            case DAVET -> {
                if (slot == SLOT_GERI) { uyelerMenu(p); return; }
                UUID uuid = sahip.slotOyuncu.get(slot);
                if (uuid == null) return;
                Player hedef = Bukkit.getPlayer(uuid);
                if (hedef == null) { m().gonder(p, "oyuncu-cevrimdisi", "&cBu oyuncu çevrimiçi değil."); davetMenu(p); return; }
                plugin.klanManager().davetEt(p, hedef);
                davetMenu(p);
            }
            case ATMA_ONAY -> {
                if (slot == SLOT_ONAY) {
                    KlanUyesi hedef = sahip.hedef == null ? null : a.uyeler.get(sahip.hedef);
                    if (hedef == null) m().gonder(p, "uye-bulunamadi", "&cKlanınızda bu isimde bir üye yok.");
                    else plugin.klanManager().at(p, hedef.isim);
                    if (plugin.klanManager().oyuncununKlani(p.getUniqueId()) != null) uyelerMenu(p);
                } else if (slot == SLOT_VAZGEC) {
                    uyelerMenu(p);
                }
            }
            case KASA -> {
                if (slot == SLOT_GERI) anaMenu(p);
                else if (slot == 33) { plugin.aidat().ode(p); kasaMenu(p); }
                else if (slot == SLOT_ESYA) plugin.esya().ac(p, 0);
            }
        }
    }

    /** Kısayol: Shift basılıyken F (eli değiştir) tuşu klan menüsünü açar. Klanı olmayanda normal çalışır. */
    @EventHandler
    public void onElDegistir(PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer();
        if (!p.isSneaking()) return;
        if (!plugin.getConfig().getBoolean("menu.kisayol-shift-f", true)) return;
        if (!p.hasPermission("klan.kullan")) return;
        if (plugin.klanManager().oyuncununKlani(p.getUniqueId()) == null) return;
        event.setCancelled(true);
        anaMenu(p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Sahip) event.setCancelled(true);
    }
}
