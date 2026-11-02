package me.klansistemi.vahsikale;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.metadata.FixedMetadataValue;

import me.klansistemi.KlanSistemi;

/**
 * Klan NPC'leri (kale nöbetçi/lider, kervan muhafızı, takviye) ölümcül vuruşta hemen ölmez, BAYILIR:
 *  - Yere düşer (yatık poz), savaşmaz, kımıldamaz. npc.bayginlik-saniye (varsayılan 45) içinde kimse kaldırmazsa ölür.
 *  - Aynı gruptan (aynı kale / aynı kervan) bir "sağlıkçı" muhafız (grubun yaklaşık üçte biri) ve grubun adrenalin iğnesi
 *    varsa baygının yanına yürüyüp onu kaldırır (iğne harcanır, can %50).
 *  - İğneler şansa bağlıdır: grubun ilk baygınlığında npc.adrenalin-sans (%) ihtimalle 1-2 iğne "bulunmuş" sayılır.
 *    Takviye (kiralık) birimlerin iğnesi yoktur.
 *  - Yerdeki NPC'ye yeniden vuran oyuncu onu öldürür (kervan yağması/kale savaşı akışı bozulmaz).
 * Durum diğer sınıflarla "koninski_baygin" ve "koninski_medik_gorev" Bukkit metadata'sı üzerinden paylaşılır.
 */
public class NpcBaygin implements Listener {

    static final String META = "koninski_baygin";
    static final String META_GOREV = "koninski_medik_gorev";

    private final KlanSistemi plugin;
    private final Random rng = new Random();
    private final Map<UUID, Long> baygin = new HashMap<>();   // NPC varlık id -> ölüm zamanı (ms)
    private final Map<UUID, UUID> gorevler = new HashMap<>(); // sağlıkçı id -> baygın id
    private final Map<String, Integer> igne = new HashMap<>();
    private final Set<String> igneYuvarlandi = new HashSet<>();

    public NpcBaygin(KlanSistemi plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    public static boolean bayginMi(Entity e) { return e != null && e.hasMetadata(META); }
    public static boolean gorevdeMi(Entity e) { return e != null && e.hasMetadata(META_GOREV); }

    private boolean aktif() { return plugin.getConfig().getBoolean("npc.bayginlik", true); }
    private int sure() { return Math.max(5, plugin.getConfig().getInt("npc.bayginlik-saniye", 45)); }
    private int igneSans() { return plugin.getConfig().getInt("npc.adrenalin-sans", 50); }

    private static boolean saglikciMi(LivingEntity le) { return Math.floorMod(le.getUniqueId().hashCode(), 3) == 0; }

    // ------------------------------------------------------------------ bayılma
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHasar(EntityDamageEvent ev) {
        if (!aktif() || !(ev.getEntity() instanceof LivingEntity le) || !CitizensKopru.npcMi(le)) return;
        String grup = plugin.vahsiKale().npcGrupAnahtari(le);
        if (grup == null) return;
        if (bayginMi(le)) return; // yerdeki NPC'ye ek vuruş öldürür
        EntityDamageEvent.DamageCause c = ev.getCause();
        if (c == EntityDamageEvent.DamageCause.VOID || c == EntityDamageEvent.DamageCause.KILL || c == EntityDamageEvent.DamageCause.SUICIDE) return;
        if (le.getHealth() - ev.getFinalDamage() > 0) return;
        ev.setCancelled(true);
        yere(le, grup);
    }

    private void yere(LivingEntity le, String grup) {
        baygin.put(le.getUniqueId(), System.currentTimeMillis() + sure() * 1000L);
        le.setMetadata(META, new FixedMetadataValue(plugin, true));
        try { le.setHealth(Math.min(2.0, maxCan(le))); } catch (Throwable ignored) { }
        CitizensKopru.dovusDurdur(le);
        poz(le, true);
        Location l = le.getLocation();
        l.getWorld().playSound(l, Sound.ENTITY_PLAYER_HURT, 1f, 0.6f);
        if (!grup.equals("takviye") && igneYuvarlandi.add(grup) && rng.nextInt(100) < igneSans()) igne.put(grup, 1 + rng.nextInt(2));
        for (Player p : l.getWorld().getPlayers()) {
            if (p.hasMetadata("NPC") || p.getLocation().distanceSquared(l) > 40 * 40) continue;
            p.sendMessage(ChatColor.GRAY + "Bir muhafız yere yığıldı! " + ChatColor.RED + "(" + sure() + " sn içinde kaldırılmazsa ölür)");
        }
    }

    private double maxCan(LivingEntity le) {
        AttributeInstance a = le.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20.0 : a.getValue();
    }

    private void poz(LivingEntity le, boolean yatik) {
        try {
            java.lang.reflect.Method m = Entity.class.getMethod("setPose", org.bukkit.entity.Pose.class, boolean.class);
            m.invoke(le, yatik ? org.bukkit.entity.Pose.SWIMMING : org.bukkit.entity.Pose.STANDING, yatik);
        } catch (Throwable ignored) { /* poz ayarlanamazsa yalnızca görsel fark olmaz */ }
    }

    // ------------------------------------------------------------------ zamanlayıcı
    private void tick() {
        long simdi = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : new ArrayList<>(baygin.entrySet())) {
            Entity en = Bukkit.getEntity(e.getKey());
            if (!(en instanceof LivingEntity le) || !le.isValid() || le.isDead()) { baygin.remove(e.getKey()); continue; }
            if (simdi >= e.getValue()) { olum(le); continue; }
            CitizensKopru.hedefVer(le, null);
            if (!gorevler.containsValue(le.getUniqueId())) medikAta(le);
        }
        // sağlıkçı görevleri
        for (Map.Entry<UUID, UUID> g : new ArrayList<>(gorevler.entrySet())) {
            Entity me = Bukkit.getEntity(g.getKey()), hasta = Bukkit.getEntity(g.getValue());
            if (!(me instanceof LivingEntity medik) || !medik.isValid() || medik.isDead() || bayginMi(medik)
                    || !(hasta instanceof LivingEntity h) || !h.isValid() || h.isDead() || !bayginMi(h)) {
                gorevler.remove(g.getKey());
                if (me != null) me.removeMetadata(META_GOREV, plugin);
                continue;
            }
            if (medik.getWorld() != h.getWorld()) { gorevBitir(medik); continue; }
            if (medik.getLocation().distanceSquared(h.getLocation()) <= 2.8 * 2.8) {
                String grup = plugin.vahsiKale().npcGrupAnahtari(h);
                int stok = grup == null ? 0 : igne.getOrDefault(grup, 0);
                gorevBitir(medik);
                if (stok > 0) { igne.put(grup, stok - 1); kaldir(h); }
            } else {
                CitizensKopru.yuruZorla(medik, h.getLocation());
            }
        }
    }

    private void gorevBitir(LivingEntity medik) {
        gorevler.remove(medik.getUniqueId());
        medik.removeMetadata(META_GOREV, plugin);
    }

    private void medikAta(LivingEntity hasta) {
        String grup = plugin.vahsiKale().npcGrupAnahtari(hasta);
        if (grup == null || grup.equals("takviye") || igne.getOrDefault(grup, 0) <= 0) return;
        LivingEntity en = null; double enMes = 45 * 45;
        for (Entity e : hasta.getWorld().getNearbyEntities(hasta.getLocation(), 45, 25, 45)) {
            if (!(e instanceof LivingEntity le) || le.equals(hasta) || !CitizensKopru.npcMi(le) || !le.isValid() || le.isDead()) continue;
            if (bayginMi(le) || gorevdeMi(le) || !saglikciMi(le)) continue;
            if (!grup.equals(plugin.vahsiKale().npcGrupAnahtari(le))) continue;
            double d = le.getLocation().distanceSquared(hasta.getLocation());
            if (d < enMes) { enMes = d; en = le; }
        }
        if (en == null) return;
        gorevler.put(en.getUniqueId(), hasta.getUniqueId());
        en.setMetadata(META_GOREV, new FixedMetadataValue(plugin, true));
    }

    private void kaldir(LivingEntity le) {
        baygin.remove(le.getUniqueId());
        le.removeMetadata(META, plugin);
        CitizensKopru.dovusGeriAl(le);
        poz(le, false);
        try { le.setHealth(Math.max(2.0, maxCan(le) * 0.5)); } catch (Throwable ignored) { }
        Location l = le.getLocation();
        l.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, l.clone().add(0, 1, 0), 15, 0.4, 0.6, 0.4, 0);
        l.getWorld().playSound(l, Sound.ITEM_TOTEM_USE, 0.6f, 1.4f);
    }

    private void olum(LivingEntity le) {
        baygin.remove(le.getUniqueId());
        le.removeMetadata(META, plugin);
        CitizensKopru.dovusGeriAl(le);
        try { le.setHealth(0.0); } catch (Throwable ignored) { }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOlum(EntityDeathEvent ev) {
        UUID id = ev.getEntity().getUniqueId();
        if (baygin.remove(id) != null) { ev.getEntity().removeMetadata(META, plugin); CitizensKopru.dovusGeriAl(ev.getEntity()); }
        if (gorevler.remove(id) != null) ev.getEntity().removeMetadata(META_GOREV, plugin);
    }
}
