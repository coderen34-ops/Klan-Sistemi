package me.ailesistemi.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.persistence.PersistentDataType;

import me.ailesistemi.AileSistemi;
import me.ailesistemi.IliskiManager;
import me.ailesistemi.Zaman;
import me.ailesistemi.model.Aile;
import me.ailesistemi.model.AileUyesi;
import me.ailesistemi.model.Gorus;
import me.ailesistemi.model.IliskiDurumu;
import me.ailesistemi.model.Rol;
import me.ailesistemi.savas.SavasKaydi;
import me.ailesistemi.Para;
import org.bukkit.event.inventory.ClickType;

/**
 * Diplomasi Paneli: sadece aile Patronu girebilir. Yetki her açılışta ve her tıklamada yeniden kontrol edilir;
 * patronluk kaybedilirse açık panel kapatılır.
 */
public class DiplomasiPanel implements Listener {

    public enum Sekme { AILELER, GIDEN, GELEN, DUELLO, SAVASLAR }

    public static class Sahip implements InventoryHolder {
        public final Sekme sekme;     // null ise aile detay ekranı
        public final int sayfa;
        public final UUID hedefAile;  // detay ekranı için
        private Inventory envanter;

        Sahip(Sekme sekme, int sayfa, UUID hedefAile) {
            this.sekme = sekme;
            this.sayfa = sayfa;
            this.hedefAile = hedefAile;
        }

        @Override
        public Inventory getInventory() { return envanter; }
    }

    private static final int[] SEKME_SLOT = {0, 2, 4, 6, 8};
    private static final int ICERIK_BAS = 9, ICERIK_SON = 44; // 36 öğe
    private static final int SLOT_ONCEKI = 45, SLOT_KAPAT = 49, SLOT_SONRAKI = 53;
    private static final int SLOT_DOST = 11, SLOT_TARAFSIZ = 13, SLOT_HUSUMET = 15, SLOT_NOT = 22, SLOT_GERI = 18, SLOT_BILGI = 4, SLOT_DUELLO = 26;

    private final AileSistemi plugin;
    private final NamespacedKey aileKey;
    private final NamespacedKey teklifKey; // "gelen" = tıklanınca kabul/red edilebilir düello teklifi

    public DiplomasiPanel(AileSistemi plugin) {
        this.plugin = plugin;
        this.aileKey = new NamespacedKey(plugin, "panel_aile");
        this.teklifKey = new NamespacedKey(plugin, "panel_teklif");
    }

    private IliskiManager iliski() { return plugin.iliski(); }

    /** Oyuncu şu an bir ailenin patronu mu? Değilse mesaj verir. */
    private Aile patronAilesi(Player p) {
        Aile a = plugin.aileManager().oyuncununAilesi(p.getUniqueId());
        if (a == null || a.uyeler.get(p.getUniqueId()).rol != Rol.PATRON) {
            plugin.mesaj().gonder(p, "panel-yetki-yok", "&cDiplomasi Paneline sadece aile Patronu (reis) girebilir.");
            return null;
        }
        return a;
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

    private Inventory olustur(Sahip sahip, int boyut, String baslik) {
        Inventory inv = Bukkit.createInventory(sahip, boyut, baslik);
        sahip.envanter = inv;
        return inv;
    }

    /** Görüş notunu açıklama satırlarına böler (satır başı ~32 karakter), tırnak içinde gösterir. */
    private static List<String> notSatirlari(String not) {
        List<String> satirlar = new ArrayList<>();
        if (not == null || not.isEmpty()) return satirlar;
        StringBuilder sb = new StringBuilder();
        for (String kelime : not.split(" ")) {
            if (sb.length() > 0 && sb.length() + kelime.length() > 32) {
                satirlar.add(sb.toString().trim());
                sb.setLength(0);
            }
            sb.append(kelime).append(' ');
        }
        if (sb.length() > 0) satirlar.add(sb.toString().trim());
        satirlar.set(0, "\"" + satirlar.get(0));
        satirlar.set(satirlar.size() - 1, satirlar.get(satirlar.size() - 1) + "\"");
        satirlar.replaceAll(satir -> ChatColor.WHITE + "" + ChatColor.ITALIC + satir);
        return satirlar;
    }

    // ------------------------------------------------------------------ SEKMELER
    public void ac(Player p, Sekme sekme, int sayfa) {
        Aile benim = patronAilesi(p);
        if (benim == null) return;

        Sahip sahip = new Sahip(sekme, sayfa, null);
        Inventory inv = olustur(sahip, 54, plugin.mesaj().metin("menu-panel-baslik", "&4&lDiplomasi Paneli &8| &7{sekme}", "sekme", sekmeAdi(sekme)));

        String[] adlar = {"Aileler", "Giden Görüşlerimiz", "Bize Gelenler", "Düello Teklifleri", "Geçmiş Savaşlar"};
        Material[] ikonlar = {Material.WHITE_BANNER, Material.WRITABLE_BOOK, Material.BOOK, Material.IRON_SWORD, Material.SHIELD};
        for (int i = 0; i < 5; i++) {
            boolean secili = Sekme.values()[i] == sekme;
            inv.setItem(SEKME_SLOT[i], esya(ikonlar[i], (secili ? ChatColor.GOLD + "» " : ChatColor.YELLOW.toString()) + adlar[i],
                    List.of(secili ? ChatColor.GREEN + "Şu an bu sekmedesiniz" : ChatColor.GRAY + "Açmak için tıkla")));
        }
        ItemStack cam = esya(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i : new int[]{1, 3, 5, 7}) inv.setItem(i, cam);

        List<ItemStack> icerik = switch (sekme) {
            case AILELER -> ailelerIcerigi(benim);
            case GIDEN -> gorusIcerigi(iliski().gidenGorusler(benim.id), true);
            case GELEN -> gorusIcerigi(iliski().gelenGorusler(benim.id), false);
            case DUELLO -> duelloIcerigi(benim);
            case SAVASLAR -> savaslarIcerigi(benim);
        };
        int sayfaBoyu = ICERIK_SON - ICERIK_BAS + 1;
        int sayfaSayisi = Math.max(1, (icerik.size() + sayfaBoyu - 1) / sayfaBoyu);
        sayfa = Math.max(0, Math.min(sayfa, sayfaSayisi - 1));
        for (int i = 0; i < sayfaBoyu && sayfa * sayfaBoyu + i < icerik.size(); i++) {
            inv.setItem(ICERIK_BAS + i, icerik.get(sayfa * sayfaBoyu + i));
        }

        for (int i = 45; i < 54; i++) inv.setItem(i, cam);
        if (sayfa > 0) inv.setItem(SLOT_ONCEKI, esya(Material.ARROW, ChatColor.YELLOW + "◄ Önceki Sayfa", List.of()));
        if (sayfa < sayfaSayisi - 1) inv.setItem(SLOT_SONRAKI, esya(Material.ARROW, ChatColor.YELLOW + "Sonraki Sayfa ►", List.of()));
        inv.setItem(SLOT_KAPAT, esya(Material.OAK_DOOR, ChatColor.RED + "Ana Menü", List.of(ChatColor.GRAY + "Sayfa " + (sayfa + 1) + "/" + sayfaSayisi)));

        p.openInventory(inv);
    }

    private static String sekmeAdi(Sekme s) {
        return switch (s) {
            case AILELER -> "Aileler";
            case GIDEN -> "Giden Görüşler";
            case GELEN -> "Bize Gelenler";
            case DUELLO -> "Düello Teklifleri";
            case SAVASLAR -> "Geçmiş Savaşlar";
        };
    }

    private List<ItemStack> ailelerIcerigi(Aile benim) {
        List<Aile> liste = new ArrayList<>(plugin.aileManager().aileler());
        liste.removeIf(a -> a.id.equals(benim.id));
        liste.sort(Comparator.comparing((Aile a) -> iliski().iliski(benim.id, a.id).ordinal()).thenComparing(a -> a.isim));
        List<ItemStack> icerik = new ArrayList<>();
        for (Aile a : liste) {
            IliskiDurumu durum = iliski().iliski(benim.id, a.id);
            AileUyesi patron = a.patron();
            Gorus benimki = iliski().gorus(benim.id, a.id);
            Gorus onlarinki = iliski().gorus(a.id, benim.id);
            List<String> aciklama = new ArrayList<>();
            aciklama.add(ChatColor.GRAY + "Patron: " + ChatColor.GOLD + (patron != null ? patron.isim : "-"));
            aciklama.add(ChatColor.GRAY + "Üye: " + ChatColor.WHITE + a.uyeler.size());
            aciklama.add(ChatColor.GRAY + "İlişki: " + durum.renkliAd());
            aciklama.add(ChatColor.GRAY + "Prestij: " + ChatColor.WHITE + a.prestij);
            int biz = benim.prestijRakip.getOrDefault(a.id, 0), onlar = a.prestijRakip.getOrDefault(benim.id, 0);
            if (biz > 0 || onlar > 0) aciklama.add(ChatColor.GRAY + "Husumet skoru: " + ChatColor.GREEN + biz + ChatColor.GRAY + " - " + ChatColor.RED + onlar);
            aciklama.add("");
            aciklama.add(ChatColor.GRAY + "Bizim görüşümüz: " + (benimki != null ? benimki.durum.renkliAd() : ChatColor.DARK_GRAY + "yok"));
            aciklama.add(ChatColor.GRAY + "Onların görüşü: " + (onlarinki != null ? onlarinki.durum.renkliAd() : ChatColor.DARK_GRAY + "yok"));
            aciklama.add("");
            aciklama.add(ChatColor.YELLOW + "► Görüş yazmak için tıkla");
            ItemStack item = esya(durum.banner, durum.renk + "" + ChatColor.BOLD + a.isim, aciklama);
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(aileKey, PersistentDataType.STRING, a.id.toString());
            item.setItemMeta(meta);
            icerik.add(item);
        }
        if (icerik.isEmpty()) icerik.add(esya(Material.BARRIER, ChatColor.GRAY + "Başka aile yok", List.of()));
        return icerik;
    }

    private List<ItemStack> duelloIcerigi(Aile benim) {
        List<ItemStack> icerik = new ArrayList<>();
        List<String> durum = plugin.savas().durumSatirlari(benim);
        icerik.add(esya(Material.IRON_SWORD, ChatColor.GOLD + "" + ChatColor.BOLD + "Savaş Durumu", durum));

        long simdi = System.currentTimeMillis();
        for (java.util.Map.Entry<UUID, Long> e : plugin.savas().gelenTeklifler(benim.id).entrySet()) {
            Aile gonderen = plugin.aileManager().aileGetir(e.getKey());
            if (gonderen == null) continue;
            ItemStack item = esya(Material.RED_BANNER, ChatColor.RED + "" + ChatColor.BOLD + "Gelen Teklif: " + gonderen.isim,
                    List.of(ChatColor.GRAY + "Gönderildi: " + ChatColor.WHITE + Zaman.sure(simdi - e.getValue()) + " önce",
                            "", ChatColor.GREEN + "Sol Tık: KABUL ET", ChatColor.RED + "Sağ Tık: REDDET"));
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(aileKey, PersistentDataType.STRING, gonderen.id.toString());
            meta.getPersistentDataContainer().set(teklifKey, PersistentDataType.STRING, "gelen");
            item.setItemMeta(meta);
            icerik.add(item);
        }
        for (java.util.Map.Entry<UUID, Long> e : plugin.savas().gidenTeklifler(benim.id).entrySet()) {
            Aile hedef = plugin.aileManager().aileGetir(e.getKey());
            if (hedef == null) continue;
            icerik.add(esya(Material.ORANGE_BANNER, ChatColor.GOLD + "Giden Teklif: " + hedef.isim,
                    List.of(ChatColor.GRAY + "Gönderildi: " + ChatColor.WHITE + Zaman.sure(simdi - e.getValue()) + " önce",
                            ChatColor.GRAY + "Karşı reisin yanıtı bekleniyor.")));
        }
        return icerik;
    }

    private List<ItemStack> savaslarIcerigi(Aile benim) {
        List<ItemStack> icerik = new ArrayList<>();
        for (SavasKaydi k : plugin.savas().gecmis()) {
            if (!k.aileA.equals(benim.id) && !k.aileB.equals(benim.id)) continue;
            boolean bizA = k.aileA.equals(benim.id);
            String rakip = bizA ? k.isimB : k.isimA;
            int biz = bizA ? k.puanA : k.puanB, onlar = bizA ? k.puanB : k.puanA;
            boolean kazandik = benim.id.equals(k.kazanan);
            Material mat;
            String baslik;
            if (k.kazanan == null) {
                mat = Material.LIGHT_GRAY_BANNER;
                baslik = ChatColor.GRAY + ("IPTAL".equals(k.sonuc) ? "İptal: " : "Berabere: ") + rakip;
            } else if (kazandik) {
                mat = Material.LIME_BANNER;
                baslik = ChatColor.GREEN + "Zafer: " + rakip;
            } else {
                mat = Material.RED_BANNER;
                baslik = ChatColor.RED + "Yenilgi: " + rakip;
            }
            List<String> aciklama = new ArrayList<>();
            aciklama.add(ChatColor.GRAY + "Skor: " + ChatColor.WHITE + biz + " - " + onlar);
            aciklama.add(ChatColor.GRAY + "Tarih: " + ChatColor.WHITE + Zaman.tarih(k.zaman));
            if (k.sure > 0) aciklama.add(ChatColor.GRAY + "Süre: " + ChatColor.WHITE + Zaman.sure(k.sure));
            if (k.kazanan != null) {
                aciklama.add(ChatColor.GRAY + (kazandik ? "Kazanılan ganimet: " : "Kaybedilen: ") + ChatColor.GOLD + Para.yaz(k.ganimetPara)
                        + ChatColor.GRAY + " + " + k.ganimetEsya + " yığın eşya");
            }
            icerik.add(esya(mat, baslik, aciklama));
        }
        if (icerik.isEmpty()) icerik.add(esya(Material.BARRIER, ChatColor.GRAY + "Henüz savaş yok", List.of()));
        return icerik;
    }

    private List<ItemStack> gorusIcerigi(List<Gorus> gorusler, boolean giden) {
        List<ItemStack> icerik = new ArrayList<>();
        for (Gorus g : gorusler) {
            Aile diger = plugin.aileManager().aileGetir(giden ? g.hedefAile : g.yazanAile);
            if (diger == null) continue;
            List<String> aciklama = new ArrayList<>();
            aciklama.add(ChatColor.GRAY + (giden ? "Hakkında: " : "Yazan aile: ") + ChatColor.GOLD + diger.isim);
            aciklama.add(ChatColor.GRAY + "Yazan: " + ChatColor.WHITE + g.yazan);
            aciklama.add(ChatColor.GRAY + "Durum: " + g.durum.renkliAd());
            aciklama.add(ChatColor.GRAY + "Tarih: " + ChatColor.WHITE + Zaman.tarih(g.zaman));
            List<String> not = notSatirlari(g.not);
            if (!not.isEmpty()) { aciklama.add(""); aciklama.addAll(not); }
            if (giden) { aciklama.add(""); aciklama.add(ChatColor.YELLOW + "► Değiştirmek için tıkla"); }
            ItemStack item = esya(g.durum.banner, g.durum.renk + "" + ChatColor.BOLD + diger.isim, aciklama);
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(aileKey, PersistentDataType.STRING, diger.id.toString());
            item.setItemMeta(meta);
            icerik.add(item);
        }
        if (icerik.isEmpty()) icerik.add(esya(Material.BARRIER, ChatColor.GRAY + "Görüş yok", List.of()));
        return icerik;
    }

    // ------------------------------------------------------------------ AİLE DETAY
    public void aileDetay(Player p, Aile hedef) {
        Aile benim = patronAilesi(p);
        if (benim == null) return;
        if (plugin.aileManager().aileGetir(hedef.id) == null) { ac(p, Sekme.AILELER, 0); return; }

        Sahip sahip = new Sahip(null, 0, hedef.id);
        Inventory inv = olustur(sahip, 27, plugin.mesaj().metin("menu-panel-detay-baslik", "&4&lDiplomasi &8| &6{aile}", "aile", hedef.isim));
        IliskiDurumu durum = iliski().iliski(benim.id, hedef.id);
        Gorus benimki = iliski().gorus(benim.id, hedef.id);
        Gorus onlarinki = iliski().gorus(hedef.id, benim.id);
        AileUyesi patron = hedef.patron();

        List<String> bilgi = new ArrayList<>();
        bilgi.add(ChatColor.GRAY + "Patron: " + ChatColor.GOLD + (patron != null ? patron.isim : "-"));
        bilgi.add(ChatColor.GRAY + "İlişki: " + durum.renkliAd());
        bilgi.add(ChatColor.GRAY + "Onların görüşü: " + (onlarinki != null ? onlarinki.durum.renkliAd() : ChatColor.DARK_GRAY + "yok"));
        if (onlarinki != null) bilgi.addAll(notSatirlari(onlarinki.not));
        inv.setItem(SLOT_BILGI, esya(durum.banner, durum.renk + "" + ChatColor.BOLD + hedef.isim, bilgi));

        String secili = benimki != null ? benimki.durum.name() : "";
        inv.setItem(SLOT_DOST, esya(Material.LIME_WOOL, ChatColor.GREEN + "" + ChatColor.BOLD + "DOST" + (secili.equals("DOST") ? " ✔" : ""),
                List.of(ChatColor.GRAY + "İki aile de DOST seçerse ittifak kurulur.",
                        ChatColor.GRAY + "Müttefikler birbirine PvP hasarı vermez.")));
        inv.setItem(SLOT_TARAFSIZ, esya(Material.LIGHT_GRAY_WOOL, ChatColor.GRAY + "" + ChatColor.BOLD + "TARAFSIZ" + (secili.equals("TARAFSIZ") ? " ✔" : ""),
                List.of(ChatColor.GRAY + "Dostluğu bozar / ilan ettiğiniz husumeti kaldırır.")));
        inv.setItem(SLOT_HUSUMET, esya(Material.RED_WOOL, ChatColor.RED + "" + ChatColor.BOLD + "HUSUMET" + (secili.equals("HUSUMET") ? " ✔" : ""),
                List.of(ChatColor.GRAY + "Tek taraflı ilan edilir, karşı aileye bildirilir.",
                        ChatColor.GRAY + "Düello teklifinin ön şartıdır.")));
        List<String> notAciklama = new ArrayList<>();
        notAciklama.add(ChatColor.GRAY + "Görüşünüze kısa bir not ekleyin.");
        if (benimki != null && !benimki.not.isEmpty()) { notAciklama.add(""); notAciklama.addAll(notSatirlari(benimki.not)); }
        notAciklama.add("");
        notAciklama.add(ChatColor.YELLOW + "► Yazmak için tıkla");
        inv.setItem(SLOT_NOT, esya(Material.WRITABLE_BOOK, ChatColor.AQUA + "" + ChatColor.BOLD + "Görüş Notu", notAciklama));
        inv.setItem(SLOT_GERI, esya(Material.ARROW, ChatColor.YELLOW + "Geri", List.of()));
        if (durum == IliskiDurumu.HUSUMET) {
            inv.setItem(SLOT_DUELLO, esya(Material.NETHERITE_SWORD, ChatColor.DARK_RED + "" + ChatColor.BOLD + "Düello Teklif Et",
                    List.of(ChatColor.GRAY + "Arenada aileler arası düello.",
                            ChatColor.GRAY + "Kazanan, kaybedenin kasasından ganimet alır.",
                            "", ChatColor.YELLOW + "► Teklif göndermek için tıkla")));
        }

        ItemStack cam = esya(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) if (inv.getItem(i) == null) inv.setItem(i, cam);
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ TIKLAMALAR
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Sahip sahip)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;

        // Yetki her tıklamada yeniden kontrol edilir
        if (patronAilesi(p) == null) { p.closeInventory(); return; }

        if (sahip.sekme == null) {
            Aile hedef = plugin.aileManager().aileGetir(sahip.hedefAile);
            if (hedef == null) { ac(p, Sekme.AILELER, 0); return; }
            switch (slot) {
                case SLOT_DOST -> { if (iliski().gorusYaz(p, hedef, IliskiDurumu.DOST, null)) aileDetay(p, hedef); else hata(p); }
                case SLOT_TARAFSIZ -> { if (iliski().gorusYaz(p, hedef, IliskiDurumu.TARAFSIZ, null)) aileDetay(p, hedef); else hata(p); }
                case SLOT_HUSUMET -> { if (iliski().gorusYaz(p, hedef, IliskiDurumu.HUSUMET, null)) aileDetay(p, hedef); else hata(p); }
                case SLOT_NOT -> { p.closeInventory(); iliski().notBekle(p, hedef); }
                case SLOT_GERI -> ac(p, Sekme.AILELER, 0);
                case SLOT_DUELLO -> {
                    if (event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.NETHERITE_SWORD) {
                        p.closeInventory();
                        plugin.savas().teklif(p, hedef.isim);
                    }
                }
                default -> { }
            }
            return;
        }

        for (int i = 0; i < SEKME_SLOT.length; i++) {
            if (slot == SEKME_SLOT[i]) { ac(p, Sekme.values()[i], 0); return; }
        }
        if (slot == SLOT_ONCEKI) { ac(p, sahip.sekme, sahip.sayfa - 1); return; }
        if (slot == SLOT_SONRAKI) { ac(p, sahip.sekme, sahip.sayfa + 1); return; }
        if (slot == SLOT_KAPAT) { plugin.menu().anaMenu(p); return; }

        if (sahip.sekme == Sekme.DUELLO && slot >= ICERIK_BAS && slot <= ICERIK_SON) {
            ItemStack item = event.getCurrentItem();
            if (item == null || !item.hasItemMeta()) return;
            ItemMeta meta = item.getItemMeta();
            if (!"gelen".equals(meta.getPersistentDataContainer().get(teklifKey, PersistentDataType.STRING))) return;
            Aile gonderen = plugin.aileManager().aileGetir(UUID.fromString(meta.getPersistentDataContainer().get(aileKey, PersistentDataType.STRING)));
            if (gonderen == null) return;
            p.closeInventory();
            if (event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT) plugin.savas().red(p, gonderen.isim);
            else plugin.savas().kabul(p, gonderen.isim);
            return;
        }

        if ((sahip.sekme == Sekme.AILELER || sahip.sekme == Sekme.GIDEN) && slot >= ICERIK_BAS && slot <= ICERIK_SON) {
            ItemStack item = event.getCurrentItem();
            if (item == null || !item.hasItemMeta()) return;
            String id = item.getItemMeta().getPersistentDataContainer().get(aileKey, PersistentDataType.STRING);
            if (id == null) return;
            Aile hedef = plugin.aileManager().aileGetir(UUID.fromString(id));
            if (hedef != null) aileDetay(p, hedef);
        }
    }

    private static void hata(Player p) {
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Sahip) event.setCancelled(true);
    }

    /** Patronluğunu kaybeden oyuncunun açık paneli kapatılır. */
    public void yetkiKontrol(Player p) {
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof Sahip) {
            Aile a = plugin.aileManager().oyuncununAilesi(p.getUniqueId());
            if (a == null || a.uyeler.get(p.getUniqueId()).rol != Rol.PATRON) p.closeInventory();
        }
    }
}
