package me.fix.vanishtab;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.*;

public class VanishTabFix extends JavaPlugin {

    private static final String SEE_PERM = "ultimatedonutsmp.staff.mode.seevanished";
    private ProtocolManager pm;
    private Object staffManager;
    private Method isVanished;
    private boolean takesUuid;
    private final Set<UUID> wasVanished = new HashSet<>();

    @Override
    public void onEnable() {
        pm = ProtocolLibrary.getProtocolManager();
        try {
            Object main = Bukkit.getPluginManager().getPlugin("UltimateDonutSmp");
            staffManager = main.getClass().getMethod("getStaffModeManager").invoke(main);
            for (Method m : staffManager.getClass().getMethods()) {
                if (m.getName().equals("isVanished") && m.getParameterCount() == 1) {
                    isVanished = m;
                    takesUuid = m.getParameterTypes()[0] == UUID.class;
                    break;
                }
            }
        } catch (Exception e) {
            getLogger().severe("Could not hook StaffModeManager: " + e);
        }
        if (isVanished == null) {
            getLogger().severe("isVanished() not found, disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Strip vanished players out of every tab-list packet sent to non-staff
        pm.addPacketListener(new PacketAdapter(this, ListenerPriority.HIGHEST, PacketType.Play.Server.PLAYER_INFO) {
            @Override
            public void onPacketSending(PacketEvent e) {
                Player viewer = e.getPlayer();
                if (viewer.hasPermission(SEE_PERM)) return;
                List<PlayerInfoData> list = e.getPacket().getPlayerInfoDataLists().read(1);
                List<PlayerInfoData> kept = new ArrayList<>();
                for (PlayerInfoData d : list) {
                    UUID id = d.getProfileId();
                    if (id != null && !id.equals(viewer.getUniqueId()) && vanished(id)) continue;
                    kept.add(d);
                }
                if (kept.size() == list.size()) return;
                if (kept.isEmpty()) { e.setCancelled(true); return; }
                e.getPacket().getPlayerInfoDataLists().write(1, kept);
            }
        });

        // Every second, make sure non-staff have no tab entry for vanished players
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            List<UUID> hidden = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) if (vanished(p.getUniqueId())) hidden.add(p.getUniqueId());

            // Anyone who just unvanished: re-add them to non-staff tab lists
            Set<UUID> now = new HashSet<>(hidden);
            for (UUID id : new ArrayList<>(wasVanished)) {
                if (now.contains(id)) continue;
                Player back = Bukkit.getPlayer(id);
                if (back != null) {
                    for (Player viewer : Bukkit.getOnlinePlayers()) {
                        if (viewer.equals(back) || viewer.hasPermission(SEE_PERM)) continue;
                        viewer.hidePlayer(this, back);
                        viewer.showPlayer(this, back);
                    }
                }
            }
            wasVanished.clear();
            wasVanished.addAll(now);

            if (hidden.isEmpty()) return;
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.hasPermission(SEE_PERM)) continue;
                List<UUID> toRemove = new ArrayList<>(hidden);
                toRemove.remove(viewer.getUniqueId());
                if (toRemove.isEmpty()) continue;
                PacketContainer rm = pm.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
                rm.getUUIDLists().write(0, toRemove);
                pm.sendServerPacket(viewer, rm);
            }
        }, 20L, 20L);
    }

    private boolean vanished(UUID id) {
        try {
            Object arg = takesUuid ? id : Bukkit.getPlayer(id);
            if (arg == null) return false;
            return (boolean) isVanished.invoke(staffManager, arg);
        } catch (Exception ex) {
            return false;
        }
    }
}
