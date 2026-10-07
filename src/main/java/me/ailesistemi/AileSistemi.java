package me.ailesistemi;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import me.ailesistemi.gui.AileMenu;
import me.ailesistemi.gui.DiplomasiPanel;
import me.ailesistemi.model.Aile;
import me.ailesistemi.savas.ArenaManager;
import me.ailesistemi.savas.SavasManager;
import me.ailesistemi.savas.GuvenliBolgeManager;
import me.ailesistemi.savas.PrestijManager;
import me.ailesistemi.komut.AcKomut;
import me.ailesistemi.komut.AileKomut;
import me.ailesistemi.veri.AileVeri;
import me.mesleksistemi.api.MeslekAPI;

public class AileSistemi extends JavaPlugin {

    private ExecutorService yazici;
    private volatile boolean kapaniyor = false;

    private MeslekAPI meslek;
    private Ayarlar ayarlar;
    private Mesaj mesaj;
    private KasaLog log;
    private AileManager aileManager;
    private AidatManager aidatManager;
    private AileVeri veri;
    private AileMenu menu;
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
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        // Dosya yazımları (families.yml, loglar) tek arka plan thread'inde sırayla yapılır
        yazici = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AileSistemi-Kayit");
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
        aileManager = new AileManager(this);
        aidatManager = new AidatManager(this);
        iliski = new IliskiManager(this);
        esya = new EsyaKasasi(this);
        arenaManager = new ArenaManager(this);
        savasManager = new SavasManager(this);
        guvenliBolgeler = new GuvenliBolgeManager(this);
        prestijManager = new PrestijManager(this);
        veri = new AileVeri(this);
        veri.yukle();
        menu = new AileMenu(this);
        panel = new DiplomasiPanel(this);

        AileKomut aileKomut = new AileKomut(this);
        PluginCommand aile = getCommand("aile");
        if (aile != null) {
            aile.setExecutor(aileKomut);
            aile.setTabCompleter(aileKomut);
        }
        PluginCommand ac = getCommand("ac");
        if (ac != null) ac.setExecutor(new AcKomut(this));

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

        getLogger().info(aileManager.aileler().size() + " aile yüklendi.");
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
        if (veri != null && aileManager != null) veri.hemenKaydet();
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
    public AileManager aileManager() { return aileManager; }
    public AidatManager aidat() { return aidatManager; }
    public AileVeri veri() { return veri; }
    public AileMenu menu() { return menu; }
    public EtiketKoprusu etiket() { return etiket; }
    public IliskiManager iliski() { return iliski; }
    public EsyaKasasi esya() { return esya; }
    public DiplomasiPanel panel() { return panel; }

    public ArenaManager arenalar() { return arenaManager; }
    public SavasManager savas() { return savasManager; }
    public GuvenliBolgeManager guvenliBolgeler() { return guvenliBolgeler; }
    public PrestijManager prestij() { return prestijManager; }

    /** Savaş kabul edildiği andan bitene kadar ailenin kasası kilitlidir. */
    public boolean kasaKilitliMi(Aile aile) {
        return savasManager != null && aile != null && savasManager.kilitliMi(aile.id);
    }
}
