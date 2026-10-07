package me.klansistemi;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import me.klansistemi.gui.KlanMenu;
import me.klansistemi.gui.DiplomasiPanel;
import me.klansistemi.model.Klan;
import me.klansistemi.savas.ArenaManager;
import me.klansistemi.savas.SavasManager;
import me.klansistemi.savas.GuvenliBolgeManager;
import me.klansistemi.savas.PrestijManager;
import me.klansistemi.komut.KcKomut;
import me.klansistemi.komut.KlanKomut;
import me.klansistemi.veri.KlanVeri;
import me.mesleksistemi.api.MeslekAPI;

public class KlanSistemi extends JavaPlugin {

    private ExecutorService yazici;
    private volatile boolean kapaniyor = false;

    private MeslekAPI meslek;
    private Ayarlar ayarlar;
    private Mesaj mesaj;
    private KasaLog log;
    private KlanManager klanManager;
    private AidatManager aidatManager;
    private KlanVeri veri;
    private KlanMenu menu;
    private EtiketKoprusu etiket;
    private IliskiManager iliski;
    private EsyaKasasi esya;
    private DiplomasiPanel panel;
    private ArenaManager arenaManager;
    private SavasManager savasManager;
    private GuvenliBolgeManager guvenliBolgeler;
    private PrestijManager prestijManager;

    @Override
    public void onEnable() {
        eskiVeriyiTasi();
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        // Dosya yazımları (klanlar.yml, loglar) tek arka plan thread'inde sırayla yapılır
        yazici = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "KlanSistemi-Kayit");
            t.setDaemon(true);
            return t;
        });

        meslek = Bukkit.getServicesManager().load(MeslekAPI.class);
        if (meslek == null) {
            getLogger().severe("MeslekSistemi'nin MeslekAPI servisi bulunamadı! MeslekSistemi'nin güncel sürümü kurulu olmalı. Eklenti kapatılıyor.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        ayarlar = new Ayarlar(this);
        mesaj = new Mesaj(this);
        log = new KasaLog(this);
        etiket = new EtiketKoprusu(this);
        klanManager = new KlanManager(this);
        aidatManager = new AidatManager(this);
        iliski = new IliskiManager(this);
        esya = new EsyaKasasi(this);
        arenaManager = new ArenaManager(this);
        savasManager = new SavasManager(this);
        guvenliBolgeler = new GuvenliBolgeManager(this);
        prestijManager = new PrestijManager(this);
        veri = new KlanVeri(this);
        veri.yukle();
        menu = new KlanMenu(this);
        panel = new DiplomasiPanel(this);

        KlanKomut klanKomut = new KlanKomut(this);
        PluginCommand klan = getCommand("klan");
        if (klan != null) {
            klan.setExecutor(klanKomut);
            klan.setTabCompleter(klanKomut);
        }
        PluginCommand ac = getCommand("kc");
        if (ac != null) ac.setExecutor(new KcKomut(this));

        getServer().getPluginManager().registerEvents(new OyuncuListener(this), this);
        getServer().getPluginManager().registerEvents(menu, this);
        getServer().getPluginManager().registerEvents(panel, this);
        getServer().getPluginManager().registerEvents(esya, this);
        getServer().getPluginManager().registerEvents(iliski, this);
        getServer().getPluginManager().registerEvents(savasManager, this);
        getServer().getPluginManager().registerEvents(prestijManager, this);

        // Aidat dönemleri, ek süreler ve hatırlatmalar dakikada bir kontrol edilir
        Bukkit.getScheduler().runTaskTimer(this, aidatManager::kontrol, 200L, 1200L);
        // Savaş geri sayımı, süre, skor çubuğu ve çıkış toleransı saniyede bir işlenir
        Bukkit.getScheduler().runTaskTimer(this, savasManager::tick, 20L, 20L);

        // PlaceholderAPI kuruluysa %klan_isim%, %klan_rol% vb. değerleri kaydet (kurulu değilse atlanır)
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new KlanPlaceholder(this).register();
            getLogger().info("PlaceholderAPI bağlantısı kuruldu (%klan_isim%, %klan_rol%, %klan_uye%, %klan_prestij%, %klan_sira%).");
        }

        getLogger().info(klanManager.klanlar().size() + " klan yüklendi.");
    }

    @Override
    public void onDisable() {
        // Süren savaşlar iptal edilir, katılımcılar eski konumlarına döner (kaydedilmeden önce)
        if (savasManager != null) savasManager.hepsiniIptalEt();
        kapaniyor = true;
        if (yazici != null) {
            yazici.shutdown();
            try {
                yazici.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (veri != null && klanManager != null) veri.hemenKaydet();
    }

    /**
     * Eklentinin eski adı "AileSistemi" idi. Sunucuda eski veri klasörü varsa ve yeni klasörde henüz kayıt yoksa
     * klanlar, arenalar, güvenli bölgeler ve loglar plugins/KlanSistemi'ye kopyalanır (eski klasör silinmez).
     * config.yml kopyalanmaz: ayar anahtarları ve mesajlar değiştiği için yeni varsayılan config oluşturulur.
     */
    private void eskiVeriyiTasi() {
        java.io.File eski = new java.io.File(getDataFolder().getParentFile(), "AileSistemi");
        java.io.File yeniKayit = new java.io.File(getDataFolder(), "klanlar.yml");
        if (!eski.isDirectory() || yeniKayit.exists()) return;
        String[][] dosyalar = {
                {"families.yml", "klanlar.yml"}, {"families.yml.bak", "klanlar.yml.bak"},
                {"arenalar.yml", "arenalar.yml"}, {"guvenli-bolgeler.yml", "guvenli-bolgeler.yml"}};
        try {
            java.nio.file.Files.createDirectories(getDataFolder().toPath());
            int sayi = 0;
            for (String[] d : dosyalar) {
                java.io.File kaynak = new java.io.File(eski, d[0]);
                if (!kaynak.isFile()) continue;
                java.nio.file.Files.copy(kaynak.toPath(), new java.io.File(getDataFolder(), d[1]).toPath());
                sayi++;
            }
            java.io.File eskiLog = new java.io.File(eski, "loglar");
            java.io.File[] loglar = eskiLog.listFiles();
            if (loglar != null) {
                java.io.File yeniLog = new java.io.File(getDataFolder(), "loglar");
                java.nio.file.Files.createDirectories(yeniLog.toPath());
                for (java.io.File log : loglar) {
                    java.io.File hedef = new java.io.File(yeniLog, log.getName());
                    if (log.isFile() && !hedef.exists()) java.nio.file.Files.copy(log.toPath(), hedef.toPath());
                }
            }
            if (sayi > 0) getLogger().info("Eski AileSistemi verileri KlanSistemi'ye taşındı (" + sayi + " dosya). Eski klasör silinmedi.");
        } catch (java.io.IOException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Eski AileSistemi verileri taşınamadı!", e);
        }
    }

    /** Dosya işini arka planda sırayla çalıştırır; kapanırken doğrudan çalıştırır. */
    public void arkaPlanda(Runnable is) {
        if (kapaniyor || yazici == null || yazici.isShutdown()) {
            is.run();
            return;
        }
        try {
            yazici.execute(is);
        } catch (RejectedExecutionException e) {
            is.run();
        }
    }

    public boolean kapaniyor() { return kapaniyor; }
    public MeslekAPI meslek() { return meslek; }
    public Ayarlar ayar() { return ayarlar; }
    public Mesaj mesaj() { return mesaj; }
    public KasaLog log() { return log; }
    public KlanManager klanManager() { return klanManager; }
    public AidatManager aidat() { return aidatManager; }
    public KlanVeri veri() { return veri; }
    public KlanMenu menu() { return menu; }
    public EtiketKoprusu etiket() { return etiket; }
    public IliskiManager iliski() { return iliski; }
    public EsyaKasasi esya() { return esya; }
    public DiplomasiPanel panel() { return panel; }

    public ArenaManager arenalar() { return arenaManager; }
    public SavasManager savas() { return savasManager; }
    public GuvenliBolgeManager guvenliBolgeler() { return guvenliBolgeler; }
    public PrestijManager prestij() { return prestijManager; }

    /** Savaş kabul edildiği andan bitene kadar klanın kasası kilitlidir. */
    public boolean kasaKilitliMi(Klan klan) {
        return savasManager != null && klan != null && savasManager.kilitliMi(klan.id);
    }
}
