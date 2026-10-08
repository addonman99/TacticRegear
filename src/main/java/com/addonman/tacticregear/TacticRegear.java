package com.addonman.tacticregear;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredItem;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.fml.common.Mod;
import java.security.SecureRandom;
import java.util.*;

@Mod(TacticRegear.ID)
public class TacticRegear {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("tacticregear");

    public static final DeferredItem<Item> LOADOUT = ITEMS.registerSimpleItem(
        "loadout",
        new Item.Properties().stacksTo(64)
    );

    public static final String ID = "tacticregear";
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final double STAND_RANGE = 16;

    public TacticRegear() {
        ITEMS.register(modEventBus); NeoForge.EVENT_BUS.addListener(TacticRegear::commands); }

    private static void commands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("regear")
            .then(Commands.literal("save").then(Commands.argument("name", StringArgumentType.word())
                .executes(c -> { try { save(c.getSource(), StringArgumentType.getString(c, "name")); return 1; } catch (Exception ex) { ex.printStackTrace(); return 0; } })))
            .then(Commands.literal("load").then(Commands.argument("name", StringArgumentType.word())
                .executes(c -> load(c.getSource(), StringArgumentType.getString(c, "name")))))
            .then(Commands.literal("list").executes(c -> { try { list(c.getSource()); return 1; } catch (Exception ex) { ex.printStackTrace(); return 0; } }))
            .then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word())
                .executes(c -> { try { delete(c.getSource(), StringArgumentType.getString(c, "name")); return 1; } catch (Exception ex) { ex.printStackTrace(); return 0; } }))));
    }

    private static int save(CommandSourceStack src, String name) throws Exception {
        ServerPlayer p = src.getPlayerOrException();
        ServerLevel level = p.serverLevel();
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) stamp(s);
        }

        Loadout l = new Loadout();
        l.inv = inv.save(new ListTag());
        l.fingerprint = fid();

        for (ArmorStand a : level.getEntitiesOfClass(ArmorStand.class, p.getBoundingBox().inflate(STAND_RANGE))) {
            ItemStack main = a.getItemBySlot(EquipmentSlot.MAINHAND);
            ItemStack off = a.getItemBySlot(EquipmentSlot.OFFHAND);
            if (main.isEmpty() && off.isEmpty()) continue;
            if (!main.isEmpty()) stamp(main);
            if (!off.isEmpty()) stamp(off);
            CompoundTag t = new CompoundTag();
            t.putUUID("id", a.getUUID());
            t.put("main", main.save(level.registryAccess()));
            t.put("off", off.save(level.registryAccess()));
            l.stands.add(t);
        }

        TacticData d = TacticData.get(level);
        d.players.computeIfAbsent(p.getUUID(), x -> new HashMap<>()).put(name, l);
        d.setDirty();
        src.sendSuccess(() -> Component.literal("Tactic Regear: saved " + name + " [" + l.fingerprint + "]"), false);
        return 1;
    }

    private static int load(CommandSourceStack src, String name) {
        try {
            ServerPlayer p = src.getPlayerOrException();
            ServerLevel level = p.serverLevel();
            Loadout l = Optional.ofNullable(TacticData.get(level).players.get(p.getUUID())).map(m -> m.get(name)).orElse(null);
            if (l == null) {
                src.sendFailure(Component.literal("Loadout not found: " + name));
                return 0;
            }

            Inventory inv = p.getInventory();
            inv.clearContent();
            inv.load(l.inv);

            for (CompoundTag t : l.stands) {
                var e = level.getEntity(t.getUUID("id"));
                if (!(e instanceof ArmorStand a)) continue;
                a.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.parseOptional(level.registryAccess(), t.getCompound("main")));
                a.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.parseOptional(level.registryAccess(), t.getCompound("off")));
            }

            p.inventoryMenu.broadcastChanges();
            src.sendSuccess(() -> Component.literal("Tactic Regear: loaded " + name + " [" + l.fingerprint + "]"), false);
            return 1;
        } catch (Exception ex) {
            src.sendFailure(Component.literal("Load failed: " + ex.getMessage()));
            return 0;
        }
    }

    private static int list(CommandSourceStack src) throws Exception {
        ServerPlayer p = src.getPlayerOrException();
        Map<String, Loadout> m = TacticData.get(p.serverLevel()).players.getOrDefault(p.getUUID(), Map.of());
        src.sendSuccess(() -> Component.literal(m.isEmpty() ? "No loadouts in this dimension." : "Loadouts: " + String.join(", ", m.keySet())), false);
        return 1;
    }

    private static int delete(CommandSourceStack src, String name) throws Exception {
        ServerPlayer p = src.getPlayerOrException();
        TacticData d = TacticData.get(p.serverLevel());
        Map<String, Loadout> m = d.players.get(p.getUUID());
        if (m == null || m.remove(name) == null) {
            src.sendFailure(Component.literal("Loadout not found: " + name));
            return 0;
        }
        d.setDirty();
        src.sendSuccess(() -> Component.literal("Tactic Regear: deleted " + name), false);
        return 1;
    }

    private static void stamp(ItemStack s) {
        ItemLore old = s.get(DataComponents.LORE);
        List<Component> lines = new ArrayList<>();
        if (old != null) lines.addAll(old.lines());
        lines.add(Component.literal("F=" + fid()).withStyle(ChatFormatting.WHITE));
        s.set(DataComponents.LORE, new ItemLore(lines));
    }

    private static String fid() {
        long x = RNG.nextLong();
        StringBuilder s = new StringBuilder(13);
        for (int i = 0; i < 13; i++) { s.append(B32[(int)(x & 31)]); x >>>= 5; }
        return s.toString();
    }

    static class Loadout {
        ListTag inv = new ListTag();
        List<CompoundTag> stands = new ArrayList<>();
        String fingerprint = "";
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.put("inv", inv);
            ListTag s = new ListTag();
            stands.forEach(s::add);
            t.put("stands", s);
            t.putString("f", fingerprint);
            return t;
        }
        static Loadout load(CompoundTag t) {
            Loadout l = new Loadout();
            l.inv = t.getList("inv", Tag.TAG_COMPOUND);
            for (Tag x : t.getList("stands", Tag.TAG_COMPOUND)) l.stands.add((CompoundTag)x);
            l.fingerprint = t.getString("f");
            return l;
        }
    }

    static class TacticData extends SavedData {
        Map<UUID, Map<String, Loadout>> players = new HashMap<>();
        static final Factory<TacticData> FACTORY = new Factory<>(TacticData::new, TacticData::load);
        static TacticData get(ServerLevel level) { return level.getDataStorage().computeIfAbsent(FACTORY, ID); }

        static TacticData load(CompoundTag t, HolderLookup.Provider p) {
            TacticData d = new TacticData();
            CompoundTag ps = t.getCompound("players");
            for (String u : ps.getAllKeys()) try {
                UUID id = UUID.fromString(u);
                CompoundTag lm = ps.getCompound(u);
                Map<String, Loadout> m = d.players.computeIfAbsent(id, x -> new HashMap<>());
                for (String n : lm.getAllKeys()) m.put(n, Loadout.load(lm.getCompound(n)));
            } catch (Exception ignored) {}
            return d;
        }

        @Override public CompoundTag save(CompoundTag t, HolderLookup.Provider p) {
            CompoundTag ps = new CompoundTag();
            players.forEach((u, m) -> {
                CompoundTag lm = new CompoundTag();
                m.forEach((n, l) -> lm.put(n, l.save()));
                ps.put(u.toString(), lm);
            });
            t.put("players", ps);
            return t;
        }
    }

    @SubscribeEvent
    public static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(LOADOUT);
        }
    }

}

