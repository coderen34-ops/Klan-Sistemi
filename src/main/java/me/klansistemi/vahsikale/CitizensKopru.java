package me.klansistemi.vahsikale;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

import me.klansistemi.KlanSistemi;

/**
 * Citizens (gerçek oyuncu tipi NPC) + Sentinel (savaş istatistikleri) köprüsü.
 * Derleme zamanı bağımlılığı YOKTUR: tüm çağrılar yansıma (reflection) ile yapılır, böylece Citizens/Sentinel
 * kurulu olmasa ya da sürümü değişse bile eklenti çökmez; yarım kalan durumda çağıran taraf eski mob tabanına döner.
 *
 * Oluşturulan NPC'ler KAYDEDİLMEZ (SHOULD_SAVE=false): kalıcı olmayan, geçici birimlerdir (takviye ekibi, müttefik destek);
 * eklenti kapanırken ya da süre bitince {@link #kaldir(LivingEntity)} ile yok edilir, yetim NPC kalmaz.
 */
final class CitizensKopru {

    private CitizensKopru() {}

    private static final Map<UUID, Object> npcler = new ConcurrentHashMap<>();

    private static boolean hazirlandi, calisiyor;
    private static Class<?> cApi, cRegistry, cNpc, cNav, cParams, cMeta, cStore, cEntityTarget;

    /** Citizens yüklü ve API'si çözümlenebiliyor mu? */
    static synchronized boolean hazir() {
        if (hazirlandi) return calisiyor;
        hazirlandi = true;
        try {
            Plugin pl = Bukkit.getPluginManager().getPlugin("Citizens");
            if (pl == null || !pl.isEnabled()) return calisiyor = false;
            cApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
            cRegistry = Class.forName("net.citizensnpcs.api.npc.NPCRegistry");
            cNpc = Class.forName("net.citizensnpcs.api.npc.NPC");
            cNav = Class.forName("net.citizensnpcs.api.ai.Navigator");
            cParams = Class.forName("net.citizensnpcs.api.ai.NavigatorParameters");
            cMeta = Class.forName("net.citizensnpcs.api.npc.NPC$Metadata");
            cStore = Class.forName("net.citizensnpcs.api.npc.MetadataStore");
            cEntityTarget = Class.forName("net.citizensnpcs.api.ai.EntityTarget");
            calisiyor = true;
        } catch (Throwable t) {
            calisiyor = false;
        }
        return calisiyor;
    }

    static boolean npcMi(Entity e) { return e != null && e.hasMetadata("NPC"); }

    private static Object kayit() throws Exception { return cApi.getMethod("getNPCRegistry").invoke(null); }

    private static Object npcOf(LivingEntity e) {
        Object n = npcler.get(e.getUniqueId());
        if (n != null) return n;
        try { return cRegistry.getMethod("getNPC", Entity.class).invoke(kayit(), e); } catch (Throwable t) { return null; }
    }

    private static void meta(Object npc, String ad, Object deger) {
        try {
            Object data = cNpc.getMethod("data").invoke(npc);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object anahtar = Enum.valueOf((Class<Enum>) cMeta, ad);
            cStore.getMethod("set", cMeta, Object.class).invoke(data, anahtar, deger);
        } catch (Throwable ignored) { /* sürümde yoksa atla */ }
    }

    /**
     * Oyuncu tipi NPC üretir ve spawn eder. Başarısızsa null (çağıran mob tabanına döner).
     * @param ad görünen isim (renk kodlu olabilir)
     * @param skin skin adı (null = Steve/Alex)
     */
    static LivingEntity uret(KlanSistemi plugin, Location l, String ad, String skin, double can, double hasar, boolean sentinel) {
        if (!hazir()) return null;
        Object npc = null;
        try {
            Object reg = kayit();
            npc = cRegistry.getMethod("createNPC", EntityType.class, String.class).invoke(reg, EntityType.PLAYER, ad);
            meta(npc, "SHOULD_SAVE", Boolean.FALSE);
            meta(npc, "DROPS_ITEMS", Boolean.FALSE);
            try { cNpc.getMethod("setProtected", boolean.class).invoke(npc, false); } catch (Throwable ignored) { }
            if (skin != null && !skin.isEmpty()) {
                try {
                    Class<?> cSkin = Class.forName("net.citizensnpcs.trait.SkinTrait");
                    Object trait = cNpc.getMethod("getOrAddTrait", Class.class).invoke(npc, cSkin);
                    cSkin.getMethod("setSkinName", String.class).invoke(trait, skin);
                } catch (Throwable t) { plugin.getLogger().fine("Citizens skin atanamadı: " + t); }
            }
            boolean ok = (Boolean) cNpc.getMethod("spawn", Location.class).invoke(npc, l);
            Entity e = (Entity) cNpc.getMethod("getEntity").invoke(npc);
            if (!ok || !(e instanceof LivingEntity le)) {
                try { cNpc.getMethod("destroy").invoke(npc); } catch (Throwable ignored) { }
                return null;
            }
            npcler.put(le.getUniqueId(), npc);
            AttributeInstance maxCan = le.getAttribute(Attribute.MAX_HEALTH);
            if (maxCan != null) maxCan.setBaseValue(can);
            le.setHealth(Math.min(can, le.getAttribute(Attribute.MAX_HEALTH) != null ? le.getAttribute(Attribute.MAX_HEALTH).getValue() : can));
            AttributeInstance hs = le.getAttribute(Attribute.ATTACK_DAMAGE);
            if (hs != null) hs.setBaseValue(hasar);
            try {
                Object nav = cNpc.getMethod("getNavigator").invoke(npc);
                Object lp = cNav.getMethod("getLocalParameters").invoke(nav);
                cParams.getMethod("range", float.class).invoke(lp, 60f);
                cParams.getMethod("attackRange", double.class).invoke(lp, 2.6);
                cParams.getMethod("speedModifier", float.class).invoke(lp, 1.15f);
            } catch (Throwable ignored) { }
            if (sentinel) sentinelAyarla(npc, can, hasar);
            return le;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Citizens NPC üretilemedi, mob tabanına dönülüyor: " + t);
            if (npc != null) { try { cNpc.getMethod("destroy").invoke(npc); } catch (Throwable ignored) { } }
            return null;
        }
    }

    /** Sentinel kuruluysa NPC'ye Sentinel eğilimi (trait) ekleyip can/hasarı ayarlar. Başarısızlık sessizce yutulur. */
    private static void sentinelAyarla(Object npc, double can, double hasar) {
        try {
            Plugin s = Bukkit.getPluginManager().getPlugin("Sentinel");
            if (s == null || !s.isEnabled()) return;
            Class<?> cSent = Class.forName("org.mcmonkey.sentinel.SentinelTrait");
            Object trait = cNpc.getMethod("getOrAddTrait", Class.class).invoke(npc, cSent);
            alanAyarla(cSent, trait, "health", can);
            alanAyarla(cSent, trait, "damage", hasar);
            alanAyarla(cSent, trait, "fightback", Boolean.FALSE); // karşılık verme/savunma kararlarını kendi kodumuz yönetir
        } catch (Throwable ignored) { }
    }

    private static void alanAyarla(Class<?> c, Object o, String ad, Object deger) {
        try {
            var f = c.getField(ad);
            Class<?> t = f.getType();
            if (deger instanceof Double d && (t == int.class)) f.set(o, d.intValue());
            else f.set(o, deger);
        } catch (Throwable ignored) { }
    }

    static LivingEntity hedefAl(LivingEntity e) {
        try {
            Object npc = npcOf(e);
            if (npc == null) return null;
            Object nav = cNpc.getMethod("getNavigator").invoke(npc);
            Object et = cNav.getMethod("getEntityTarget").invoke(nav);
            if (et == null) return null;
            Object t = cEntityTarget.getMethod("getTarget").invoke(et);
            return t instanceof LivingEntity le ? le : null;
        } catch (Throwable t) { return null; }
    }

    /** Hedefe saldırgan (aggressive) yürüyüş; hedef null ise gezinmeyi iptal eder. */
    static void hedefVer(LivingEntity e, LivingEntity hedef) {
        try {
            Object npc = npcOf(e);
            if (npc == null) return;
            Object nav = cNpc.getMethod("getNavigator").invoke(npc);
            if (hedef == null) { cNav.getMethod("cancelNavigation").invoke(nav); return; }
            cNav.getMethod("setTarget", Entity.class, boolean.class).invoke(nav, hedef, true);
        } catch (Throwable ignored) { }
    }

    static void yuru(LivingEntity e, Location l) {
        try {
            Object npc = npcOf(e);
            if (npc == null) return;
            Object nav = cNpc.getMethod("getNavigator").invoke(npc);
            cNav.getMethod("setTarget", Location.class).invoke(nav, l);
        } catch (Throwable ignored) { }
    }

    static void isinla(LivingEntity e, Location l) {
        try {
            Object npc = npcOf(e);
            if (npc == null) { e.teleport(l); return; }
            try { cNav.getMethod("cancelNavigation").invoke(cNpc.getMethod("getNavigator").invoke(npc)); } catch (Throwable ignored) { }
            cNpc.getMethod("teleport", Location.class, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class)
                    .invoke(npc, l, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        } catch (Throwable t) { try { e.teleport(l); } catch (Throwable ignored) { } }
    }

    static void kaldir(LivingEntity e) {
        Object npc = npcler.remove(e.getUniqueId());
        try {
            if (npc == null) npc = npcOf(e);
            if (npc != null) cNpc.getMethod("destroy").invoke(npc);
        } catch (Throwable ignored) { }
        try { if (e.isValid()) e.remove(); } catch (Throwable ignored) { }
    }

    /** Eklenti kapanırken bu eklentinin oluşturduğu tüm NPC'leri yok eder. */
    static void hepsiniKaldir() {
        for (Object npc : npcler.values()) {
            try { cNpc.getMethod("destroy").invoke(npc); } catch (Throwable ignored) { }
        }
        npcler.clear();
    }

}
