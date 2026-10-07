package me.klansistemi.komut;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import me.klansistemi.KlanManager;
import me.klansistemi.KlanSistemi;
import me.klansistemi.Mesaj;
import me.klansistemi.Para;
import me.klansistemi.Zaman;
import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;
import me.klansistemi.model.IliskiDurumu;
import me.klansistemi.gui.DiplomasiPanel;
import me.klansistemi.model.Rol;

public class KlanKomut implements TabExecutor {

    private static final List<String> ALT_KOMUTLAR = List.of(
            "kur", "davet", "katil", "ayril", "at", "bilgi", "liste", "yatir", "cek", "kasa",
            "terfi", "indir", "kidem", "dagit", "sohbet", "aidat", "panel", "iliski", "savas", "prestij", "uzmanlik", "atolye", "buyu", "ganimet", "yardim");
    private static final int LISTE_SAYFA = 10;

    private final KlanSistemi plugin;

    public KlanKomut(KlanSistemi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }
    private KlanManager am() { return plugin.klanManager(); }

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
        if (!p.hasPermission("klan.kullan")) { m().gonder(p, "yetki-yok", "&cBu işlem için yetkiniz yok."); return true; }

        if (args.length == 0) {
            if (am().oyuncununKlani(p.getUniqueId()) != null) plugin.menu().anaMenu(p);
            else yardim(p);
            return true;
        }

        String alt = args[0].toLowerCase(Locale.ROOT);
        switch (alt) {
            case "kur" -> {
                if (args.length < 2) { kullanim(p, "/klan kur <isim>"); return true; }
                am().kur(p, args[1]);
            }
            case "davet" -> {
                if (args.length < 2) { kullanim(p, "/klan davet <oyuncu>"); return true; }
                Player hedef = Bukkit.getPlayerExact(args[1]);
                if (hedef == null) { m().gonder(p, "oyuncu-cevrimdisi", "&cBu oyuncu çevrimiçi değil."); return true; }
                am().davetEt(p, hedef);
            }
            case "katil" -> {
                if (args.length < 2) { kullanim(p, "/klan katil <klan>"); return true; }
                am().katil(p, args[1]);
            }
            case "ayril" -> am().ayril(p, args.length > 1 && args[1].equalsIgnoreCase("onayla"));
            case "at" -> {
                if (args.length < 2) { kullanim(p, "/klan at <oyuncu>"); return true; }
                am().at(p, args[1]);
            }
            case "bilgi" -> bilgi(p, args.length > 1 ? args[1] : null);
            case "liste" -> liste(p, args.length > 1 ? sayi(args[1], 1) : 1);
            case "yatir" -> {
                if (args.length < 2) { kullanim(p, "/klan yatir <miktar>"); return true; }
                am().yatir(p, Para.oku(args[1]));
            }
            case "cek" -> {
                if (args.length < 2) { kullanim(p, "/klan cek <miktar>"); return true; }
                am().cek(p, Para.oku(args[1]));
            }
            case "kasa" -> {
                if (am().oyuncununKlani(p.getUniqueId()) == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return true; }
                plugin.menu().kasaMenu(p);
            }
            case "terfi" -> {
                if (args.length < 2) { kullanim(p, "/klan terfi <oyuncu>"); return true; }
                am().terfi(p, args[1]);
            }
            case "indir" -> {
                if (args.length < 2) { kullanim(p, "/klan indir <oyuncu>"); return true; }
                am().indir(p, args[1]);
            }
            case "kidem" -> {
                if (args.length < 3) { kullanim(p, "/klan kidem <yardımcı> <sıra>"); return true; }
                int sira = sayi(args[2], -1);
                if (sira < 1) { m().gonder(p, "gecersiz-sayi", "&cGeçerli bir sayı yazın."); return true; }
                am().kidem(p, args[1], sira);
            }
            case "dagit" -> am().dagit(p, args.length > 1 && args[1].equalsIgnoreCase("onayla"));
            case "sohbet" -> am().sohbetDegistir(p);
            case "aidat" -> aidat(p, args);
            case "panel" -> plugin.panel().ac(p, DiplomasiPanel.Sekme.KLANLAR, 0);
            case "iliski" -> iliski(p, args);
            case "ganimet" -> plugin.esya().bekleyenleriVer(p);
            case "savas" -> savas(p, args);
            case "atolye" -> plugin.atolye().ac(p);
            case "buyu" -> plugin.buyuUstasi().komut(p, args);
            case "uzmanlik" -> {
                String islem = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "liste";
                switch (islem) {
                    case "al" -> {
                        if (args.length < 3) { kullanim(p, "/klan uzmanlik al <alan>"); return true; }
                        plugin.uzmanlik().al(p, args[2]);
                    }
                    case "birak" -> plugin.uzmanlik().birak(p, args.length > 2 ? args[2] : null);
                    default -> plugin.uzmanlik().liste(p);
                }
            }
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
                if (args.length < 3) { kullanim(p, "/klan aidat ayarla <miktar>"); return; }
                plugin.aidat().ayarla(p, Para.oku(args[2]));
            }
            default -> plugin.aidat().bilgi(p);
        }
    }

    // /klan savas <teklif|kabul|red|katil|birak|durum>
    private void savas(Player p, String[] args) {
        String islem = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "durum";
        String klan = args.length > 2 ? args[2] : null;
        switch (islem) {
            case "teklif" -> {
                if (klan == null) { kullanim(p, "/klan savas teklif <klan>"); return; }
                plugin.savas().teklif(p, klan);
            }
            case "kabul" -> plugin.savas().kabul(p, klan);
            case "red" -> plugin.savas().red(p, klan);
            case "katil" -> plugin.savas().katil(p);
            case "birak" -> plugin.savas().birak(p);
            default -> plugin.savas().durum(p);
        }
    }

    // /klan iliski liste | /klan iliski <klan> <dost|tarafsiz|husumet> [görüş notu]
    private void iliski(Player p, String[] args) {
        Klan benim = am().oyuncununKlani(p.getUniqueId());
        if (benim == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        if (args.length < 2 || args[1].equalsIgnoreCase("liste")) {
            p.sendMessage(m().metin("iliski-liste-baslik", "&6&l--- {klan} İLİŞKİLERİ ---", "klan", benim.isim));
            boolean bos = true;
            for (Klan diger : am().klanlar()) {
                if (diger.id.equals(benim.id)) continue;
                IliskiDurumu d = plugin.iliski().iliski(benim.id, diger.id);
                if (d == IliskiDurumu.TARAFSIZ) continue;
                p.sendMessage(Mesaj.renk("&7- &f" + diger.isim + "&7: ") + d.renkliAd());
                bos = false;
            }
            if (bos) p.sendMessage(m().metin("iliski-liste-bos", "&7Tüm klanlarla tarafsızsınız."));
            return;
        }
        if (benim.uyeler.get(p.getUniqueId()).rol != Rol.LIDER) { m().gonder(p, "sadece-lider", "&cBunu sadece klan Lideri yapabilir."); return; }
        if (args.length < 3) { kullanim(p, "/klan iliski <klan> <dost|tarafsiz|husumet> [not]"); return; }
        Klan hedef = am().klanBul(args[1]);
        if (hedef == null) { m().gonder(p, "klan-yok", "&cBöyle bir klan bulunamadı."); return; }
        IliskiDurumu durum = IliskiDurumu.oku(args[2]);
        if (durum == null) { kullanim(p, "/klan iliski <klan> <dost|tarafsiz|husumet> [not]"); return; }
        String not = args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : null;
        plugin.iliski().gorusYaz(p, hedef, durum, not);
    }

    private void bilgi(Player p, String klanIsmi) {
        Klan a = klanIsmi == null ? am().oyuncununKlani(p.getUniqueId()) : am().klanBul(klanIsmi);
        if (a == null) {
            if (klanIsmi == null) m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz.");
            else m().gonder(p, "klan-yok", "&cBöyle bir klan bulunamadı.");
            return;
        }
        boolean uyesi = a.uyeler.containsKey(p.getUniqueId());
        KlanUyesi lider = a.lider();
        p.sendMessage(m().metin("bilgi-baslik", "&6&l--- {klan} ---", "klan", a.isim));
        p.sendMessage(m().metin("bilgi-lider", "&7Lider: &6{lider}", "lider", lider != null ? lider.isim : "-"));
        p.sendMessage(m().metin("bilgi-kurulus", "&7Kuruluş: &f{tarih}", "tarih", Zaman.tarih(a.kurulus)));
        p.sendMessage(m().metin("bilgi-uye-sayisi", "&7Üyeler: &f{sayi}/{max}", "sayi", a.uyeler.size(), "max", plugin.ayar().maxUye()));
        p.sendMessage(m().metin("bilgi-uzmanlik", "&7Uzmanlık: {alanlar}", "alanlar", plugin.uzmanlik().alanlarMetni(a)));
        p.sendMessage(m().metin("bilgi-prestij", "&7Prestij: &a{prestij} &7({sira}. sırada)", "prestij", a.prestij, "sira", plugin.prestij().sira(a)));
        StringBuilder sb = new StringBuilder();
        for (KlanUyesi u : a.siraliUyeler()) {
            boolean cevrimici = Bukkit.getPlayer(u.uuid) != null;
            sb.append(u.rol.renk).append(cevrimici ? "● " : "○ ").append(u.isim).append(" ");
        }
        p.sendMessage(sb.toString().trim());
        if (uyesi) p.sendMessage(m().metin("bilgi-kasa", "&7Kasa: &a{kasa}", "kasa", Para.yaz(a.kasa)));
        long koruma = a.kurulus + plugin.ayar().yeniKlanKorumaMs() - System.currentTimeMillis();
        if (koruma > 0) p.sendMessage(m().metin("bilgi-koruma", "&7Yeni klan koruması: &b{sure} &7(savaşa girilemez)", "sure", Zaman.sure(koruma)));
    }

    private void liste(Player p, int sayfa) {
        List<Klan> liste = new ArrayList<>(am().klanlar());
        if (liste.isEmpty()) { m().gonder(p, "liste-bos", "&7Henüz hiç klan kurulmadı."); return; }
        liste.sort(Comparator.comparingInt((Klan a) -> a.uyeler.size()).reversed().thenComparing(a -> a.isim));
        int sayfaSayisi = (liste.size() + LISTE_SAYFA - 1) / LISTE_SAYFA;
        sayfa = Math.max(1, Math.min(sayfa, sayfaSayisi));
        p.sendMessage(m().metin("liste-baslik", "&6&l--- KLANLAR ({sayfa}/{toplam}) ---", "sayfa", sayfa, "toplam", sayfaSayisi));
        for (int i = (sayfa - 1) * LISTE_SAYFA; i < Math.min(liste.size(), sayfa * LISTE_SAYFA); i++) {
            Klan a = liste.get(i);
            KlanUyesi lider = a.lider();
            p.sendMessage(m().metin("liste-satir", "&e{sira}. &6{klan} &7- Lider: &f{lider} &7- {uye} üye &7- &a{prestij} prestij",
                    "sira", i + 1, "klan", a.isim, "lider", lider != null ? lider.isim : "-", "uye", a.uyeler.size(), "prestij", a.prestij));
        }
        if (sayfa < sayfaSayisi) p.sendMessage(m().metin("liste-sonraki", "&7Sonraki sayfa: &e/klan liste {sayfa}", "sayfa", sayfa + 1));
    }

    private void admin(CommandSender s, String[] args) {
        if (!s.hasPermission("klan.admin")) { m().gonder(s, "yetki-yok", "&cBu işlem için yetkiniz yok."); return; }
        if (args.length < 2) { kullanim(s, "/klan admin <arena|guvenli|savas bitir|kasa duzelt|dagit|etiketyenile|yenile>"); return; }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "kasa" -> {
                if (args.length < 5 || !args[2].equalsIgnoreCase("duzelt")) { kullanim(s, "/klan admin kasa duzelt <klan> <miktar>"); return; }
                Klan a = am().klanBul(args[3]);
                if (a == null) { m().gonder(s, "klan-yok", "&cBöyle bir klan bulunamadı."); return; }
                double miktar;
                try {
                    miktar = Double.parseDouble(args[4]);
                    if (!Double.isFinite(miktar) || miktar < 0) throw new NumberFormatException();
                } catch (NumberFormatException e) { m().gonder(s, "gecersiz-miktar", "&cGeçerli bir miktar yazın."); return; }
                am().adminKasaDuzelt(s, a, miktar);
            }
            case "dagit" -> {
                if (args.length < 3) { kullanim(s, "/klan admin dagit <klan>"); return; }
                Klan a = am().klanBul(args[2]);
                if (a == null) { m().gonder(s, "klan-yok", "&cBöyle bir klan bulunamadı."); return; }
                am().adminDagit(s, a);
            }
            case "etiketyenile" -> {
                int sayi = plugin.etiket().tumunuYenile();
                if (sayi < 0) m().gonder(s, "etiket-kapali", "&cEtiket entegrasyonu kapalı ya da TagPlugin kurulu değil.");
                else m().gonder(s, "etiket-yenilendi", "&a{sayi} oyuncunun klan etiketi yeniden uygulandı.", "sayi", sayi);
            }
            case "arena" -> plugin.arenalar().komut(s, args);
            case "guvenli" -> plugin.guvenliBolgeler().komut(s, args);
            case "buyu" -> {
                if (args.length >= 5 && args[2].equalsIgnoreCase("uret")) plugin.uzmanlik().adminUret(s, args[3], args[4], args.length > 5 ? args[5] : null);
                else if (args.length >= 4 && args[2].equalsIgnoreCase("defter")) plugin.uzmanlik().adminDefter(s, args[3]);
                else if (args.length >= 4 && args[2].equalsIgnoreCase("npc")) plugin.buyuUstasi().npcKomut(s, args[3]);
                else kullanim(s, "/klan admin buyu <uret <klan> <alan> [eşya] | defter <kod> | npc <kur|sil>>");
            }
            case "savas" -> {
                if (args.length < 4 || !args[2].equalsIgnoreCase("bitir")) { kullanim(s, "/klan admin savas bitir <klan>"); return; }
                Klan a = am().klanBul(args[3]);
                if (a == null) { m().gonder(s, "klan-yok", "&cBöyle bir klan bulunamadı."); return; }
                plugin.savas().adminBitir(s, a);
            }
            case "yenile" -> {
                plugin.reloadConfig();
                m().gonder(s, "admin-yenilendi", "&aKlan ayarları yeniden yüklendi.");
            }
            default -> kullanim(s, "/klan admin <arena|guvenli|savas bitir|kasa duzelt|dagit|etiketyenile|yenile>");
        }
    }

    private void yardim(CommandSender s) {
        s.sendMessage(m().metin("yardim-baslik", "&6&l--- KLAN KOMUTLARI ---"));
        String[][] satirlar = {
                {"kur <isim>", "Klan kur ({ucret})"}, {"davet <oyuncu>", "Klana davet et"}, {"katil <klan>", "Davet edildiğin klana katıl"},
                {"ayril", "Klandan ayrıl"}, {"at <oyuncu>", "Üyeyi at"}, {"bilgi [klan]", "Klan bilgisi"}, {"liste", "Tüm klanlar"},
                {"yatir <miktar>", "Kasaya para yatır"}, {"cek <miktar>", "Kasadan para çek"}, {"kasa", "Kasa menüsü"},
                {"terfi <oyuncu>", "Yardımcı yap"}, {"indir <oyuncu>", "Üye'ye indir"}, {"kidem <yardımcı> <sıra>", "Halef sırası"},
                {"dagit onayla", "Klanı dağıt"}, {"sohbet", "Klan sohbetini aç/kapat (/kc <mesaj>)"},
                {"aidat <ode|bilgi|ayarla>", "Aidat işlemleri"}, {"panel", "Diplomasi Paneli (Lider)"},
                {"iliski <klan> <dost|tarafsiz|husumet> [not]", "Görüş/ilişki (Lider)"}, {"iliski liste", "İlişkilerimiz"},
                {"savas teklif <klan>", "Düello teklif et (Lider, husumet şart)"}, {"savas <kabul|red> [klan]", "Teklifi yanıtla (Lider)"},
                {"savas katil", "Hazırlıktaki savaşa katıl"}, {"savas birak", "Savaştan ayrıl"}, {"savas", "Savaş durumu"},
                {"prestij [sayfa]", "Klan prestij sıralaması"}, {"uzmanlik <liste|al|birak>", "Büyü alanları (Lider)"}, {"atolye", "Büyülü eşya üretimi (klan kasasından)"}, {"buyu [fiyat|fiyatlar|gecmis]", "Büyü Ustası (ticari büyü satışı)"}, {"ganimet", "Bekleyen eşyalarını al"}};
        for (String[] satir : satirlar) {
            s.sendMessage(Mesaj.renk("&e/klan " + satir[0] + " &7- " + satir[1].replace("{ucret}", Para.yaz(plugin.ayar().kurmaUcreti()))));
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
            if (sender.hasPermission("klan.admin")) oneriler.add("admin");
        } else if (sender instanceof Player p) {
            Klan a = am().oyuncununKlani(p.getUniqueId());
            String alt = args[0].toLowerCase(Locale.ROOT);
            if (args.length == 2) {
                switch (alt) {
                    case "davet" -> Bukkit.getOnlinePlayers().forEach(o -> { if (am().oyuncununKlani(o.getUniqueId()) == null) oneriler.add(o.getName()); });
                    case "katil", "bilgi" -> am().klanlar().forEach(x -> oneriler.add(x.isim));
                    case "iliski" -> {
                        oneriler.add("liste");
                        am().klanlar().forEach(x -> { if (a == null || !x.id.equals(a.id)) oneriler.add(x.isim); });
                    }
                    case "at", "terfi", "indir", "kidem" -> {
                        if (a != null) for (KlanUyesi u : a.uyeler.values()) {
                            if (u.uuid.equals(p.getUniqueId())) continue;
                            if (alt.equals("terfi") && u.rol != Rol.UYE) continue;
                            if ((alt.equals("indir") || alt.equals("kidem")) && u.rol != Rol.YARDIMCI) continue;
                            oneriler.add(u.isim);
                        }
                    }
                    case "ayril", "dagit" -> oneriler.add("onayla");
                    case "aidat" -> oneriler.addAll(List.of("ode", "bilgi", "ayarla"));
                    case "admin" -> { if (p.hasPermission("klan.admin")) oneriler.addAll(List.of("arena", "guvenli", "buyu", "savas", "kasa", "dagit", "etiketyenile", "yenile")); }
                    case "uzmanlik" -> oneriler.addAll(List.of("liste", "al", "birak"));
                    case "buyu" -> oneriler.addAll(List.of("fiyat", "fiyatlar", "gecmis"));
                    case "savas" -> oneriler.addAll(List.of("teklif", "kabul", "red", "katil", "birak", "durum"));
                    default -> { }
                }
            } else if (args.length == 3) {
                if (alt.equals("savas") && (args[1].equalsIgnoreCase("teklif") || args[1].equalsIgnoreCase("kabul") || args[1].equalsIgnoreCase("red"))) {
                    am().klanlar().forEach(x -> { if (a == null || !x.id.equals(a.id)) oneriler.add(x.isim); });
                } else if (alt.equals("admin") && p.hasPermission("klan.admin") && args[1].equalsIgnoreCase("arena")) {
                    oneriler.addAll(List.of("kur", "sil", "pos1", "pos2", "spawn1", "spawn2", "liste"));
                } else if (alt.equals("uzmanlik") && (args[1].equalsIgnoreCase("al") || args[1].equalsIgnoreCase("birak"))) {
                    oneriler.addAll(plugin.uzmanlik().alanlar().keySet());
                } else if (alt.equals("admin") && p.hasPermission("klan.admin") && args[1].equalsIgnoreCase("buyu")) {
                    oneriler.addAll(List.of("uret", "defter", "npc"));
                } else if (alt.equals("buyu") && args[1].equalsIgnoreCase("fiyat")) {
                    oneriler.addAll(plugin.buyuUstasi().tumBuyuAdlari());
                } else if (alt.equals("admin") && p.hasPermission("klan.admin") && args[1].equalsIgnoreCase("guvenli")) {
                    oneriler.addAll(List.of("merkez", "kur", "sil", "pos1", "pos2", "liste"));
                } else if (alt.equals("admin") && p.hasPermission("klan.admin") && args[1].equalsIgnoreCase("savas")) {
                    oneriler.add("bitir");
                } else if (alt.equals("iliski") && !args[1].equalsIgnoreCase("liste")) {
                    oneriler.addAll(List.of("dost", "tarafsiz", "husumet"));
                } else if (alt.equals("kidem") && a != null) {
                    for (int i = 1; i <= a.yardimcilar().size(); i++) oneriler.add(String.valueOf(i));
                } else if (alt.equals("admin") && p.hasPermission("klan.admin")) {
                    if (args[1].equalsIgnoreCase("kasa")) oneriler.add("duzelt");
                    else if (args[1].equalsIgnoreCase("dagit")) am().klanlar().forEach(x -> oneriler.add(x.isim));
                }
            } else if (args.length == 4 && alt.equals("admin") && p.hasPermission("klan.admin")) {
                if (args[1].equalsIgnoreCase("kasa") || args[1].equalsIgnoreCase("savas")) am().klanlar().forEach(x -> oneriler.add(x.isim));
                else if (args[1].equalsIgnoreCase("arena") && !args[2].equalsIgnoreCase("kur")) oneriler.addAll(plugin.arenalar().isimler());
                else if (args[1].equalsIgnoreCase("guvenli") && !args[2].equalsIgnoreCase("kur")) oneriler.addAll(plugin.guvenliBolgeler().isimler());
                else if (args[1].equalsIgnoreCase("buyu") && args[2].equalsIgnoreCase("uret")) am().klanlar().forEach(x -> oneriler.add(x.isim));
                else if (args[1].equalsIgnoreCase("buyu") && args[2].equalsIgnoreCase("npc")) oneriler.addAll(List.of("kur", "sil"));
            } else if (args.length == 5 && alt.equals("admin") && p.hasPermission("klan.admin") && args[1].equalsIgnoreCase("buyu") && args[2].equalsIgnoreCase("uret")) {
                oneriler.addAll(plugin.uzmanlik().alanlar().keySet());
            }
        }
        String yazilan = args[args.length - 1].toLowerCase(Locale.ROOT);
        oneriler.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(yazilan));
        return oneriler;
    }
}
