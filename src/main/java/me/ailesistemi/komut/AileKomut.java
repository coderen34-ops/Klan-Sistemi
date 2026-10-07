package me.ailesistemi.komut;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import me.ailesistemi.AileManager;
import me.ailesistemi.AileSistemi;
import me.ailesistemi.Mesaj;
import me.ailesistemi.Para;
import me.ailesistemi.Zaman;
import me.ailesistemi.model.Aile;
import me.ailesistemi.model.AileUyesi;
import me.ailesistemi.model.IliskiDurumu;
import me.ailesistemi.gui.DiplomasiPanel;
import me.ailesistemi.model.Rol;

public class AileKomut implements TabExecutor {

    private static final List<String> ALT_KOMUTLAR = List.of(
            "kur", "davet", "katil", "ayril", "at", "bilgi", "liste", "yatir", "cek", "kasa",
            "terfi", "indir", "kidem", "dagit", "sohbet", "aidat", "panel", "iliski", "savas", "prestij", "ganimet", "yardim");
    private static final int LISTE_SAYFA = 10;

    private final AileSistemi plugin;

    public AileKomut(AileSistemi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }
    private AileManager am() { return plugin.aileManager(); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            admin(sender, args);
            return true;
        }
        if (!(sender instanceof Player p)) {
            m().gonder(sender, "sadece-oyuncu", "&cBu komut sadece oyun içinden kullanılabilir.");
            return true;
        }
        if (!p.hasPermission("aile.kullan")) { m().gonder(p, "yetki-yok", "&cBu işlem için yetkiniz yok."); return true; }

        if (args.length == 0) {
            if (am().oyuncununAilesi(p.getUniqueId()) != null) plugin.menu().anaMenu(p);
            else yardim(p);
            return true;
        }

        String alt = args[0].toLowerCase(Locale.ROOT);
        switch (alt) {
            case "kur" -> {
                if (args.length < 2) { kullanim(p, "/aile kur <isim>"); return true; }
                am().kur(p, args[1]);
            }
            case "davet" -> {
                if (args.length < 2) { kullanim(p, "/aile davet <oyuncu>"); return true; }
                Player hedef = Bukkit.getPlayerExact(args[1]);
                if (hedef == null) { m().gonder(p, "oyuncu-cevrimdisi", "&cBu oyuncu çevrimiçi değil."); return true; }
                am().davetEt(p, hedef);
            }
            case "katil" -> {
                if (args.length < 2) { kullanim(p, "/aile katil <aile>"); return true; }
                am().katil(p, args[1]);
            }
            case "ayril" -> am().ayril(p, args.length > 1 && args[1].equalsIgnoreCase("onayla"));
            case "at" -> {
                if (args.length < 2) { kullanim(p, "/aile at <oyuncu>"); return true; }
                am().at(p, args[1]);
            }
            case "bilgi" -> bilgi(p, args.length > 1 ? args[1] : null);
            case "liste" -> liste(p, args.length > 1 ? sayi(args[1], 1) : 1);
            case "yatir" -> {
                if (args.length < 2) { kullanim(p, "/aile yatir <miktar>"); return true; }
                am().yatir(p, Para.oku(args[1]));
            }
            case "cek" -> {
                if (args.length < 2) { kullanim(p, "/aile cek <miktar>"); return true; }
                am().cek(p, Para.oku(args[1]));
            }
            case "kasa" -> {
                if (am().oyuncununAilesi(p.getUniqueId()) == null) { m().gonder(p, "ailede-degil", "&cBir ailede değilsiniz."); return true; }
                plugin.menu().kasaMenu(p);
            }
            case "terfi" -> {
                if (args.length < 2) { kullanim(p, "/aile terfi <oyuncu>"); return true; }
                am().terfi(p, args[1]);
            }
            case "indir" -> {
                if (args.length < 2) { kullanim(p, "/aile indir <oyuncu>"); return true; }
                am().indir(p, args[1]);
            }
            case "kidem" -> {
                if (args.length < 3) { kullanim(p, "/aile kidem <yardımcı> <sıra>"); return true; }
                int sira = sayi(args[2], -1);
                if (sira < 1) { m().gonder(p, "gecersiz-sayi", "&cGeçerli bir sayı yazın."); return true; }
                am().kidem(p, args[1], sira);
            }
            case "dagit" -> am().dagit(p, args.length > 1 && args[1].equalsIgnoreCase("onayla"));
            case "sohbet" -> am().sohbetDegistir(p);
            case "aidat" -> aidat(p, args);
            case "panel" -> plugin.panel().ac(p, DiplomasiPanel.Sekme.AILELER, 0);
            case "iliski" -> iliski(p, args);
            case "ganimet" -> plugin.esya().bekleyenleriVer(p);
            case "savas" -> savas(p, args);
            case "prestij", "top" -> plugin.prestij().siralama(p, args.length > 1 ? sayi(args[1], 1) : 1);
            default -> yardim(p);
        }
        return true;
    }

    private void aidat(Player p, String[] args) {
        String islem = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "bilgi";
        switch (islem) {
            case "ode", "öde" -> plugin.aidat().ode(p);
            case "ayarla" -> {
                if (args.length < 3) { kullanim(p, "/aile aidat ayarla <miktar>"); return; }
                plugin.aidat().ayarla(p, Para.oku(args[2]));
            }
            default -> plugin.aidat().bilgi(p);
        }
    }

    // /aile savas <teklif|kabul|red|katil|birak|durum>
    private void savas(Player p, String[] args) {
        String islem = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "durum";
        String aile = args.length > 2 ? args[2] : null;
        switch (islem) {
            case "teklif" -> {
                if (aile == null) { kullanim(p, "/aile savas teklif <aile>"); return; }
                plugin.savas().teklif(p, aile);
            }
            case "kabul" -> plugin.savas().kabul(p, aile);
            case "red" -> plugin.savas().red(p, aile);
            case "katil" -> plugin.savas().katil(p);
            case "birak" -> plugin.savas().birak(p);
            default -> plugin.savas().durum(p);
        }
    }

    // /aile iliski liste | /aile iliski <aile> <dost|tarafsiz|husumet> [görüş notu]
    private void iliski(Player p, String[] args) {
        Aile benim = am().oyuncununAilesi(p.getUniqueId());
        if (benim == null) { m().gonder(p, "ailede-degil", "&cBir ailede değilsiniz."); return; }
        if (args.length < 2 || args[1].equalsIgnoreCase("liste")) {
            p.sendMessage(m().metin("iliski-liste-baslik", "&6&l--- {aile} İLİŞKİLERİ ---", "aile", benim.isim));
            boolean bos = true;
            for (Aile diger : am().aileler()) {
                if (diger.id.equals(benim.id)) continue;
                IliskiDurumu d = plugin.iliski().iliski(benim.id, diger.id);
                if (d == IliskiDurumu.TARAFSIZ) continue;
                p.sendMessage(Mesaj.renk("&7- &f" + diger.isim + "&7: ") + d.renkliAd());
                bos = false;
            }
            if (bos) p.sendMessage(m().metin("iliski-liste-bos", "&7Tüm ailelerle tarafsızsınız."));
            return;
        }
        if (benim.uyeler.get(p.getUniqueId()).rol != Rol.PATRON) { m().gonder(p, "sadece-patron", "&cBunu sadece aile Patronu yapabilir."); return; }
        if (args.length < 3) { kullanim(p, "/aile iliski <aile> <dost|tarafsiz|husumet> [not]"); return; }
        Aile hedef = am().aileBul(args[1]);
        if (hedef == null) { m().gonder(p, "aile-yok", "&cBöyle bir aile bulunamadı."); return; }
        IliskiDurumu durum = IliskiDurumu.oku(args[2]);
        if (durum == null) { kullanim(p, "/aile iliski <aile> <dost|tarafsiz|husumet> [not]"); return; }
        String not = args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : null;
        plugin.iliski().gorusYaz(p, hedef, durum, not);
    }

    private void bilgi(Player p, String aileIsmi) {
        Aile a = aileIsmi == null ? am().oyuncununAilesi(p.getUniqueId()) : am().aileBul(aileIsmi);
        if (a == null) {
            if (aileIsmi == null) m().gonder(p, "ailede-degil", "&cBir ailede değilsiniz.");
            else m().gonder(p, "aile-yok", "&cBöyle bir aile bulunamadı.");
            return;
        }
        boolean uyesi = a.uyeler.containsKey(p.getUniqueId());
        AileUyesi patron = a.patron();
        p.sendMessage(m().metin("bilgi-baslik", "&6&l--- {aile} ---", "aile", a.isim));
        p.sendMessage(m().metin("bilgi-patron", "&7Patron: &6{patron}", "patron", patron != null ? patron.isim : "-"));
        p.sendMessage(m().metin("bilgi-kurulus", "&7Kuruluş: &f{tarih}", "tarih", Zaman.tarih(a.kurulus)));
        p.sendMessage(m().metin("bilgi-uye-sayisi", "&7Üyeler: &f{sayi}/{max}", "sayi", a.uyeler.size(), "max", plugin.ayar().maxUye()));
        p.sendMessage(m().metin("bilgi-prestij", "&7Prestij: &a{prestij} &7({sira}. sırada)", "prestij", a.prestij, "sira", plugin.prestij().sira(a)));
        StringBuilder sb = new StringBuilder();
        for (AileUyesi u : a.siraliUyeler()) {
            boolean cevrimici = Bukkit.getPlayer(u.uuid) != null;
            sb.append(u.rol.renk).append(cevrimici ? "● " : "○ ").append(u.isim).append(" ");
        }
        p.sendMessage(sb.toString().trim());
        if (uyesi) p.sendMessage(m().metin("bilgi-kasa", "&7Kasa: &a{kasa}", "kasa", Para.yaz(a.kasa)));
        long koruma = a.kurulus + plugin.ayar().yeniAileKorumaMs() - System.currentTimeMillis();
        if (koruma > 0) p.sendMessage(m().metin("bilgi-koruma", "&7Yeni aile koruması: &b{sure} &7(savaşa girilemez)", "sure", Zaman.sure(koruma)));
    }

    private void liste(Player p, int sayfa) {
        List<Aile> liste = new ArrayList<>(am().aileler());
        if (liste.isEmpty()) { m().gonder(p, "liste-bos", "&7Henüz hiç aile kurulmadı."); return; }
        liste.sort(Comparator.comparingInt((Aile a) -> a.uyeler.size()).reversed().thenComparing(a -> a.isim));
        int sayfaSayisi = (liste.size() + LISTE_SAYFA - 1) / LISTE_SAYFA;
        sayfa = Math.max(1, Math.min(sayfa, sayfaSayisi));
        p.sendMessage(m().metin("liste-baslik", "&6&l--- AİLELER ({sayfa}/{toplam}) ---", "sayfa", sayfa, "toplam", sayfaSayisi));
        for (int i = (sayfa - 1) * LISTE_SAYFA; i < Math.min(liste.size(), sayfa * LISTE_SAYFA); i++) {
            Aile a = liste.get(i);
            AileUyesi patron = a.patron();
            p.sendMessage(m().metin("liste-satir", "&e{sira}. &6{aile} &7- Patron: &f{patron} &7- {uye} üye &7- &a{prestij} prestij",
                    "sira", i + 1, "aile", a.isim, "patron", patron != null ? patron.isim : "-", "uye", a.uyeler.size(), "prestij", a.prestij));
        }
        if (sayfa < sayfaSayisi) p.sendMessage(m().metin("liste-sonraki", "&7Sonraki sayfa: &e/aile liste {sayfa}", "sayfa", sayfa + 1));
    }

    private void admin(CommandSender s, String[] args) {
        if (!s.hasPermission("aile.admin")) { m().gonder(s, "yetki-yok", "&cBu işlem için yetkiniz yok."); return; }
        if (args.length < 2) { kullanim(s, "/aile admin <arena|guvenli|savas bitir|kasa duzelt|dagit|etiketyenile|yenile>"); return; }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "kasa" -> {
                if (args.length < 5 || !args[2].equalsIgnoreCase("duzelt")) { kullanim(s, "/aile admin kasa duzelt <aile> <miktar>"); return; }
                Aile a = am().aileBul(args[3]);
                if (a == null) { m().gonder(s, "aile-yok", "&cBöyle bir aile bulunamadı."); return; }
                double miktar;
                try {
                    miktar = Double.parseDouble(args[4]);
                    if (!Double.isFinite(miktar) || miktar < 0) throw new NumberFormatException();
                } catch (NumberFormatException e) { m().gonder(s, "gecersiz-miktar", "&cGeçerli bir miktar yazın."); return; }
                am().adminKasaDuzelt(s, a, miktar);
            }
            case "dagit" -> {
                if (args.length < 3) { kullanim(s, "/aile admin dagit <aile>"); return; }
                Aile a = am().aileBul(args[2]);
                if (a == null) { m().gonder(s, "aile-yok", "&cBöyle bir aile bulunamadı."); return; }
                am().adminDagit(s, a);
            }
            case "etiketyenile" -> {
                int sayi = plugin.etiket().tumunuYenile();
                if (sayi < 0) m().gonder(s, "etiket-kapali", "&cEtiket entegrasyonu kapalı ya da TagPlugin kurulu değil.");
                else m().gonder(s, "etiket-yenilendi", "&a{sayi} oyuncunun aile etiketi yeniden uygulandı.", "sayi", sayi);
            }
            case "arena" -> plugin.arenalar().komut(s, args);
            case "guvenli" -> plugin.guvenliBolgeler().komut(s, args);
            case "savas" -> {
                if (args.length < 4 || !args[2].equalsIgnoreCase("bitir")) { kullanim(s, "/aile admin savas bitir <aile>"); return; }
                Aile a = am().aileBul(args[3]);
                if (a == null) { m().gonder(s, "aile-yok", "&cBöyle bir aile bulunamadı."); return; }
                plugin.savas().adminBitir(s, a);
            }
            case "yenile" -> {
                plugin.reloadConfig();
                m().gonder(s, "admin-yenilendi", "&aAile ayarları yeniden yüklendi.");
            }
            default -> kullanim(s, "/aile admin <arena|guvenli|savas bitir|kasa duzelt|dagit|etiketyenile|yenile>");
        }
    }

    private void yardim(CommandSender s) {
        s.sendMessage(m().metin("yardim-baslik", "&6&l--- AİLE KOMUTLARI ---"));
        String[][] satirlar = {
                {"kur <isim>", "Aile kur ({ucret})"}, {"davet <oyuncu>", "Aileye davet et"}, {"katil <aile>", "Davet edildiğin aileye katıl"},
                {"ayril", "Aileden ayrıl"}, {"at <oyuncu>", "Üyeyi at"}, {"bilgi [aile]", "Aile bilgisi"}, {"liste", "Tüm aileler"},
                {"yatir <miktar>", "Kasaya para yatır"}, {"cek <miktar>", "Kasadan para çek"}, {"kasa", "Kasa menüsü"},
                {"terfi <oyuncu>", "Yardımcı yap"}, {"indir <oyuncu>", "Üye'ye indir"}, {"kidem <yardımcı> <sıra>", "Halef sırası"},
                {"dagit onayla", "Aileyi dağıt"}, {"sohbet", "Aile sohbetini aç/kapat (/ac <mesaj>)"},
                {"aidat <ode|bilgi|ayarla>", "Aidat işlemleri"}, {"panel", "Diplomasi Paneli (Patron)"},
                {"iliski <aile> <dost|tarafsiz|husumet> [not]", "Görüş/ilişki (Patron)"}, {"iliski liste", "İlişkilerimiz"},
                {"savas teklif <aile>", "Düello teklif et (Patron, husumet şart)"}, {"savas <kabul|red> [aile]", "Teklifi yanıtla (Patron)"},
                {"savas katil", "Hazırlıktaki savaşa katıl"}, {"savas birak", "Savaştan ayrıl"}, {"savas", "Savaş durumu"},
                {"prestij [sayfa]", "Aile prestij sıralaması"}, {"ganimet", "Bekleyen eşyalarını al"}};
        for (String[] satir : satirlar) {
            s.sendMessage(Mesaj.renk("&e/aile " + satir[0] + " &7- " + satir[1].replace("{ucret}", Para.yaz(plugin.ayar().kurmaUcreti()))));
        }
    }

    private void kullanim(CommandSender s, String kullanim) {
        m().gonder(s, "kullanim", "&cKullanım: &e{kullanim}", "kullanim", kullanim);
    }

    private static int sayi(String metin, int varsayilan) {
        try { return Integer.parseInt(metin); } catch (NumberFormatException e) { return varsayilan; }
    }

    // ------------------------------------------------------------------ TAB TAMAMLAMA
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> oneriler = new ArrayList<>();
        if (args.length == 1) {
            oneriler.addAll(ALT_KOMUTLAR);
            if (sender.hasPermission("aile.admin")) oneriler.add("admin");
        } else if (sender instanceof Player p) {
            Aile a = am().oyuncununAilesi(p.getUniqueId());
            String alt = args[0].toLowerCase(Locale.ROOT);
            if (args.length == 2) {
                switch (alt) {
                    case "davet" -> Bukkit.getOnlinePlayers().forEach(o -> { if (am().oyuncununAilesi(o.getUniqueId()) == null) oneriler.add(o.getName()); });
                    case "katil", "bilgi" -> am().aileler().forEach(x -> oneriler.add(x.isim));
                    case "iliski" -> {
                        oneriler.add("liste");
                        am().aileler().forEach(x -> { if (a == null || !x.id.equals(a.id)) oneriler.add(x.isim); });
                    }
                    case "at", "terfi", "indir", "kidem" -> {
                        if (a != null) for (AileUyesi u : a.uyeler.values()) {
                            if (u.uuid.equals(p.getUniqueId())) continue;
                            if (alt.equals("terfi") && u.rol != Rol.UYE) continue;
                            if ((alt.equals("indir") || alt.equals("kidem")) && u.rol != Rol.YARDIMCI) continue;
                            oneriler.add(u.isim);
                        }
                    }
                    case "ayril", "dagit" -> oneriler.add("onayla");
                    case "aidat" -> oneriler.addAll(List.of("ode", "bilgi", "ayarla"));
                    case "admin" -> { if (p.hasPermission("aile.admin")) oneriler.addAll(List.of("arena", "guvenli", "savas", "kasa", "dagit", "etiketyenile", "yenile")); }
                    case "savas" -> oneriler.addAll(List.of("teklif", "kabul", "red", "katil", "birak", "durum"));
                    default -> { }
                }
            } else if (args.length == 3) {
                if (alt.equals("savas") && (args[1].equalsIgnoreCase("teklif") || args[1].equalsIgnoreCase("kabul") || args[1].equalsIgnoreCase("red"))) {
                    am().aileler().forEach(x -> { if (a == null || !x.id.equals(a.id)) oneriler.add(x.isim); });
                } else if (alt.equals("admin") && p.hasPermission("aile.admin") && args[1].equalsIgnoreCase("arena")) {
                    oneriler.addAll(List.of("kur", "sil", "pos1", "pos2", "spawn1", "spawn2", "liste"));
                } else if (alt.equals("admin") && p.hasPermission("aile.admin") && args[1].equalsIgnoreCase("guvenli")) {
                    oneriler.addAll(List.of("merkez", "kur", "sil", "pos1", "pos2", "liste"));
                } else if (alt.equals("admin") && p.hasPermission("aile.admin") && args[1].equalsIgnoreCase("savas")) {
                    oneriler.add("bitir");
                } else if (alt.equals("iliski") && !args[1].equalsIgnoreCase("liste")) {
                    oneriler.addAll(List.of("dost", "tarafsiz", "husumet"));
                } else if (alt.equals("kidem") && a != null) {
                    for (int i = 1; i <= a.yardimcilar().size(); i++) oneriler.add(String.valueOf(i));
                } else if (alt.equals("admin") && p.hasPermission("aile.admin")) {
                    if (args[1].equalsIgnoreCase("kasa")) oneriler.add("duzelt");
                    else if (args[1].equalsIgnoreCase("dagit")) am().aileler().forEach(x -> oneriler.add(x.isim));
                }
            } else if (args.length == 4 && alt.equals("admin") && p.hasPermission("aile.admin")) {
                if (args[1].equalsIgnoreCase("kasa") || args[1].equalsIgnoreCase("savas")) am().aileler().forEach(x -> oneriler.add(x.isim));
                else if (args[1].equalsIgnoreCase("arena") && !args[2].equalsIgnoreCase("kur")) oneriler.addAll(plugin.arenalar().isimler());
                else if (args[1].equalsIgnoreCase("guvenli") && !args[2].equalsIgnoreCase("kur")) oneriler.addAll(plugin.guvenliBolgeler().isimler());
            }
        }
        String yazilan = args[args.length - 1].toLowerCase(Locale.ROOT);
        oneriler.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(yazilan));
        return oneriler;
    }
}
