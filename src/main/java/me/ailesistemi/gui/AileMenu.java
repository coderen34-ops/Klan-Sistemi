package me.ailesistemi.gui;

import java.util.ArrayList;
import java.util.List;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import me.ailesistemi.AidatManager;
import me.ailesistemi.AileSistemi;
import me.ailesistemi.Mesaj;
import me.ailesistemi.Para;
import me.ailesistemi.Zaman;
import me.ailesistemi.model.Aile;
import me.ailesistemi.model.AileUyesi;
import me.ailesistemi.model.Rol;

/**
 * Aile menüleri. Menüler başlık yazısıyla değil kendi InventoryHolder tipiyle tanınır;
 * böylece aynı isimli bir sandık ya da başka eklentinin menüsüyle karışmaz.
 */
public class AileMenu implements Listener {

    public enum Tip { ANA, UYELER, KASA }

    /** Menüyü tanımlayan sahip: hangi menü, hangi aile. */
    public static class Sahip implements InventoryHolder {
        public final Tip tip;
        public final UUID aileId;
        private Inventory envanter;

        Sahip(Tip tip, UUID aileId) {
            this.tip = tip;
            this.aileId = aileId;
        }

        @Override
        public Inventory getInventory() { return envanter; }
    }

    private static final int SLOT_BILGI = 4, SLOT_UYELER = 10, SLOT_KASA = 12, SLOT_AIDAT = 14, SLOT_SOHBET = 16, SLOT_YARDIM = 22;
    private static final int SLOT_ILISKILER = 20, SLOT_DIPLOMASI = 24, SLOT_ESYA = 31;
    private static final int SLOT_GERI = 49;

    private final AileSistemi plugin;

    public AileMenu(AileSistemi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }

    private Inventory olustur(Tip tip, Aile a, int boyut, String baslik) {
        Sahip sahip = new Sahip(tip, a.id);
        Inventory inv = Bukkit.createInventory(sahip, boyut, baslik);
        sahip.envanter = inv;
        return inv;
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
        Aile a = plugin.aileManager().oyuncununAilesi(p.getUniqueId());
        if (a == null) return;
        AileUyesi ben = a.uyeler.get(p.getUniqueId());
        AidatManager aidat = plugin.aidat();
        long simdi = System.currentTimeMillis();

        Inventory inv = olustur(Tip.ANA, a, 27, m().metin("menu-ana-baslik", "&6&l{aile} &8| &7Aile Menüsü", "aile", a.isim));

        AileUyesi patron = a.patron();
        List<String> bilgi = new ArrayList<>();
        bilgi.add(ChatColor.GRAY + "Patron: " + ChatColor.GOLD + (patron != null ? patron.isim : "-"));
        bilgi.add(ChatColor.GRAY + "Kuruluş: " + ChatColor.WHITE + Zaman.tarih(a.kurulus));
        bilgi.add(ChatColor.GRAY + "Üyeler: " + ChatColor.WHITE + a.uyeler.size() + "/" + plugin.ayar().maxUye());
        bilgi.add(ChatColor.GRAY + "Kasa: " + ChatColor.GREEN + Para.yaz(a.kasa));
        bilgi.add(ChatColor.GRAY + "Rolünüz: " + ben.rol.renkliAd());
        inv.setItem(SLOT_BILGI, esya(Material.WHITE_BANNER, ChatColor.GOLD + "" + ChatColor.BOLD + a.isim, bilgi));

        inv.setItem(SLOT_UYELER, esya(Material.PLAYER_HEAD, ChatColor.YELLOW + "" + ChatColor.BOLD + "Üyeler",
                List.of(ChatColor.GRAY + "Aile üyelerini ve rollerini gör.",
                        ben.rol == Rol.PATRON ? ChatColor.AQUA + "Aidat durumları da görünür." : "",
                        "", ChatColor.YELLOW + "► Açmak için tıkla")));

        inv.setItem(SLOT_KASA, esya(Material.GOLD_INGOT, ChatColor.GOLD + "" + ChatColor.BOLD + "Aile Kasası",
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

        boolean sohbetAcik = plugin.aileManager().sohbetAcikMi(p.getUniqueId());
        inv.setItem(SLOT_SOHBET, esya(sohbetAcik ? Material.WRITABLE_BOOK : Material.BOOK,
                ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Aile Sohbeti",
                List.of(ChatColor.GRAY + "Durum: " + (sohbetAcik ? ChatColor.GREEN + "AÇIK" : ChatColor.RED + "KAPALI"),
                        ChatColor.GRAY + "Tek mesaj için: " + ChatColor.WHITE + "/ac <mesaj>",
                        "", ChatColor.YELLOW + "► Açmak/kapatmak için tıkla")));

        int[] iliskiOzeti = plugin.iliski().ozet(a.id);
        inv.setItem(SLOT_ILISKILER, esya(Material.WHITE_BANNER, ChatColor.WHITE + "" + ChatColor.BOLD + "İlişkiler",
                List.of(ChatColor.GREEN + "Dost: " + iliskiOzeti[0],
                        ChatColor.RED + "Husumet: " + iliskiOzeti[1],
                        ChatColor.GRAY + "Diğerleri: Tarafsız",
                        "", ChatColor.GRAY + "Ayrıntı: " + ChatColor.WHITE + "/aile iliski liste")));
        if (ben.rol == Rol.PATRON) {
            inv.setItem(SLOT_DIPLOMASI, esya(Material.RED_BANNER, ChatColor.DARK_RED + "" + ChatColor.BOLD + "Diplomasi Paneli",
                    List.of(ChatColor.GRAY + "Sadece Patron görebilir.",
                            ChatColor.GRAY + "Diğer aileler hakkında görüş yaz,",
                            ChatColor.GRAY + "ittifak kur ya da husumet ilan et.",
                            "", ChatColor.YELLOW + "► Açmak için tıkla")));
        }

        inv.setItem(SLOT_YARDIM, esya(Material.KNOWLEDGE_BOOK, ChatColor.GREEN + "" + ChatColor.BOLD + "Komutlar",
                List.of(ChatColor.GRAY + "Tüm aile komutlarını gör.", "", ChatColor.YELLOW + "► Tıkla")));

        doldur(inv);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
    }

    // ------------------------------------------------------------------ ÜYELER
    public void uyelerMenu(Player p) {
        Aile a = plugin.aileManager().oyuncununAilesi(p.getUniqueId());
        if (a == null) return;
        boolean patronMu = a.uyeler.get(p.getUniqueId()).rol == Rol.PATRON;
        AidatManager aidat = plugin.aidat();
        long simdi = System.currentTimeMillis();

        Inventory inv = olustur(Tip.UYELER, a, 54, m().metin("menu-uyeler-baslik", "&6&l{aile} &8| &7Üyeler", "aile", a.isim));
        int slot = 0;
        for (AileUyesi u : a.siraliUyeler()) {
            if (slot >= 45) break;
            ItemStack kafa = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) kafa.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(u.uuid));
            boolean cevrimici = Bukkit.getPlayer(u.uuid) != null;
            meta.setDisplayName(u.rol.renk + "" + ChatColor.BOLD + u.isim + (cevrimici ? ChatColor.GREEN + " ●" : ChatColor.DARK_GRAY + " ○"));
            List<String> aciklama = new ArrayList<>();
            aciklama.add(ChatColor.GRAY + "Rol: " + u.rol.renkliAd() + (u.rol == Rol.YARDIMCI ? ChatColor.GRAY + " (Kıdem " + u.kidem + ")" : ""));
            aciklama.add(ChatColor.GRAY + "Katılma: " + ChatColor.WHITE + Zaman.tarih(u.katilma));
            if (patronMu) {
                aciklama.add("");
                aciklama.add(ChatColor.GRAY + "Aidat: " + aidat.durum(a, u, simdi).etiket);
                aciklama.add(ChatColor.GRAY + "Son ödeme: " + ChatColor.WHITE + (u.sonOdeme > 0 ? Zaman.tarih(u.sonOdeme) : "-"));
                if (u.ardisikOdenmeyen > 0) aciklama.add(ChatColor.RED + "Üst üste ödenmeyen: " + u.ardisikOdenmeyen);
            }
            meta.setLore(aciklama);
            kafa.setItemMeta(meta);
            inv.setItem(slot++, kafa);
        }
        inv.setItem(SLOT_GERI, esya(Material.ARROW, ChatColor.YELLOW + "Geri", List.of()));
        doldur(inv);
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ KASA
    public void kasaMenu(Player p) {
        Aile a = plugin.aileManager().oyuncununAilesi(p.getUniqueId());
        if (a == null) return;
        AileUyesi ben = a.uyeler.get(p.getUniqueId());
        int[] ist = plugin.aidat().istatistik(a);

        Inventory inv = olustur(Tip.KASA, a, 54, m().metin("menu-kasa-baslik", "&6&l{aile} &8| &7Kasa", "aile", a.isim));
        inv.setItem(13, esya(Material.GOLD_BLOCK, ChatColor.GOLD + "" + ChatColor.BOLD + "Kasa Bakiyesi",
                List.of(ChatColor.GREEN + Para.yaz(a.kasa),
                        ChatColor.GRAY + "Üst sınır: " + ChatColor.WHITE + Para.yaz(plugin.ayar().kasaUstSinir()),
                        "",
                        ChatColor.GRAY + "Yatırmak: " + ChatColor.WHITE + "/aile yatir <miktar>",
                        ben.rol == Rol.UYE ? ChatColor.DARK_GRAY + "Üyeler para çekemez." : ChatColor.GRAY + "Çekmek: " + ChatColor.WHITE + "/aile cek <miktar>",
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
                List.of(ChatColor.GRAY + "Ailenin ortak eşya deposu.",
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

        // Menü açıkken aileden çıkarıldıysa ya da aile dağıldıysa işlem yapılmaz
        Aile a = plugin.aileManager().oyuncununAilesi(p.getUniqueId());
        if (a == null || !a.id.equals(sahip.aileId)) { p.closeInventory(); return; }

        int slot = event.getRawSlot();
        switch (sahip.tip) {
            case ANA -> {
                if (slot == SLOT_UYELER) uyelerMenu(p);
                else if (slot == SLOT_KASA) kasaMenu(p);
                else if (slot == SLOT_AIDAT) { plugin.aidat().ode(p); anaMenu(p); }
                else if (slot == SLOT_SOHBET) { plugin.aileManager().sohbetDegistir(p); anaMenu(p); }
                else if (slot == SLOT_YARDIM) { p.closeInventory(); p.performCommand("aile yardim"); }
                else if (slot == SLOT_DIPLOMASI) plugin.panel().ac(p, me.ailesistemi.gui.DiplomasiPanel.Sekme.AILELER, 0);
            }
            case UYELER -> { if (slot == SLOT_GERI) anaMenu(p); }
            case KASA -> {
                if (slot == SLOT_GERI) anaMenu(p);
                else if (slot == 33) { plugin.aidat().ode(p); kasaMenu(p); }
                else if (slot == SLOT_ESYA) plugin.esya().ac(p, 0);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Sahip) event.setCancelled(true);
    }
}
