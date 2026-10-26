package dev.guildquests.gui;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.fx.FxPersistentState;
import dev.guildquests.fx.FxSettings;
import dev.guildquests.service.AdminLoanPolicy;
import dev.guildquests.service.BossEvent;
import dev.guildquests.service.GuildService;
import dev.guildquests.util.Numbers;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Vanilla chest menu; every selection, permission check and mutation lives on the server.
 * Exposes every admin command: reload, boss, coins add/set, loan add/set/clear,
 * delinquent on/off and the FX operations (margin-call toggle, reload, crash/rally).
 */
public final class AdminScreenHandler extends GuildMenu {
    private final ServerPlayer owner;
    private final SimpleContainer display = new SimpleContainer(54);
    private final Map<Integer, java.util.function.IntConsumer> actions = new HashMap<>();
    private String view = "PLAYERS", operation = "", notice = "";
    private UUID target;
    private int page;
    private long amount, reviewedLoan, reviewedCoins;
    private boolean reviewedLock;
    private String fxSymbol;

    public AdminScreenHandler(int id, net.minecraft.world.Container inventory, ServerPlayer owner) {
        super(GuildMenuKind.ADMIN, id); this.owner = owner;
        for (int i = 0; i < 54; i++) addSlot(new Slot(display, i, 8 + i % 9 * 18, 18 + i / 9 * 18) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
        });
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 198));
        populate();
    }
    private static boolean allowed(ServerPlayer player) { return player.createCommandSourceStack().hasPermission(2); }
    public static void open(ServerPlayer player) {
        if (!allowed(player)) return;
        player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new AdminScreenHandler(id, inventory, player), Component.literal("ギルド管理")));
    }
    private GuildPersistentState state() { return GuildPersistentState.get(owner.getServer()); }
    private PlayerGuildData data() { return state().get(target); }
    private String name(UUID id) {
        var online = owner.getServer().getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        String known = state().get(id).playerName;
        return known == null || known.isBlank() ? id.toString().substring(0, 8) : known;
    }
    private void set(int slot, Item item, String title, String... lore) { ItemStack stack = GuildItems.item(item, title, lore); if (slot == 13 && view.equals("AMOUNT")) GuildItems.amount(stack, amount, Integer.MAX_VALUE, "入力額"); display.setItem(slot, stack); }
    private void action(int slot, Item item, String title, Runnable action, String... lore) {
        set(slot, item, title, lore); actions.put(slot, b -> action.run());
    }
    /** Action on a prebuilt stack (e.g. a digit head) that receives the mouse button. */
    private void actionStack(int slot, ItemStack stack, java.util.function.IntConsumer action) {
        display.setItem(slot, stack); actions.put(slot, action);
    }
    @Override public boolean setGuiAmount(long value) {
        if (!(view.equals("AMOUNT") && allowed(owner)) || value < 0 || value > Integer.MAX_VALUE) return false;
        amount = value;
        populate();
        return true;
    }
    @Override protected void populate() {
        actions.clear();
        for (int i = 0; i < 54; i++) set(i, Items.GRAY_STAINED_GLASS_PANE, " ");
        if (view.equals("PLAYERS")) players();
        else if (view.equals("SERVER")) server();
        else {
            set(4, Items.PLAYER_HEAD, "対象: " + name(target), "UUID: " + target, "所持金: " + Numbers.gc(data().coins),
                    "借入: " + Numbers.gc(data().loan), "FX・リボ債務: " + Numbers.gc(data().debt), "滞納: " + (data().delinquent ? "あり" : "なし"));
            switch (view) { case "DETAIL" -> detail(); case "AMOUNT" -> input(); case "CONFIRM" -> confirmation(); }
        }
        if (!notice.isEmpty()) set(0, Items.PAPER, notice);
        actions.keySet().forEach(slot -> GuildItems.action(display.getItem(slot)));
    }
    private void players() {
        for (var online : owner.getServer().getPlayerList().getPlayers()) GuildService.data(online);
        List<UUID> ids = new ArrayList<>(state().players().keySet());
        ids.sort(Comparator.<UUID>comparingInt(id -> owner.getServer().getPlayerList().getPlayer(id) == null ? 1 : 0).thenComparing(this::name, String.CASE_INSENSITIVE_ORDER).thenComparing(UUID::toString));
        int pages = Math.max(1, (ids.size() + 26) / 27); page = Math.max(0, Math.min(page, pages - 1));
        set(4, Items.BOOK, "管理するプレイヤーを選択", "オンライン・保存済みプレイヤー", "ページ " + (page + 1) + " / " + pages, "権限: OPレベル2以上");
        for (int i = page * 27; i < Math.min(ids.size(), (page + 1) * 27); i++) {
            UUID id = ids.get(i); var wallet = state().get(id); int slot = 9 + i % 27;
            display.setItem(slot, GuildItems.playerHead(owner.getServer(), id, name(id), name(id),
                    owner.getServer().getPlayerList().getPlayer(id) == null ? "オフライン" : "オンライン", "所持金: " + Numbers.gc(wallet.coins),
                    "借入: " + Numbers.gc(wallet.loan), "FX・リボ債務: " + Numbers.gc(wallet.debt), "滞納: " + (wallet.delinquent ? "あり" : "なし"), "クリックで管理"));
            actions.put(slot, b -> { target = id; view = "DETAIL"; notice = ""; });
        }
        if (page > 0) action(45, Items.ARROW, "前のページ", () -> page--);
        if (page + 1 < pages) action(53, Items.ARROW, "次のページ", () -> page++);
        action(50, Items.COMMAND_BLOCK, "サーバー操作", () -> { view = "SERVER"; notice = ""; },
                "設定再読込 / 緊急ボス / FX操作", "すべての管理コマンドをUIから実行");
        action(49, Items.BARRIER, "閉じる", owner::closeContainer);
    }
    private void detail() {
        action(10, Items.EMERALD, "借入を追加", () -> beginAmount("ADD"), "loan add と同じ操作", "所持金は増やさず、借入だけ加算");
        action(12, Items.WRITABLE_BOOK, "借入を指定額に設定", () -> beginAmount("SET"), "loan set と同じ操作", "0以上の整数GCを入力");
        action(14, Items.TNT, "借入を帳消し", () -> review("CLEAR"), "loan clear と同じ操作", "FX・リボ債務は変更しません", "次の画面で確認して確定");
        action(16, Items.GOLD_NUGGET, "コインを追加", () -> beginAmount("COINS_ADD"), "coins add と同じ操作", "所持金を増やします");
        action(17, Items.GOLD_BLOCK, "コインを設定", () -> beginAmount("COINS_SET"), "coins set と同じ操作", "所持金を指定額へ変更");
        set(22, Items.PAPER, "現在の管理状態", "所持金: " + Numbers.gc(data().coins), "借入: " + Numbers.gc(data().loan), "FX・リボ債務: " + Numbers.gc(data().debt),
                "滞納: " + (data().delinquent ? "あり" : "なし"), "管理者による固定ロック: " + (data().adminDelinquent ? "あり" : "なし"));
        boolean locked = data().delinquent;
        action(31, locked ? Items.REDSTONE_BLOCK : Items.LIME_DYE,
                "滞納ロック: " + (locked ? "あり" : "なし") + "（クリックで切替）",
                () -> review(locked ? "OFF" : "ON"),
                "delinquent " + (locked ? "off" : "on") + " と同じ操作",
                locked ? "管理者ロックを解除します" : "管理者が解除するまでロックを維持");
        action(49, Items.ARROW, "プレイヤー選択へ戻る", () -> view = "PLAYERS");
    }
    private void server() {
        set(4, Items.COMMAND_BLOCK, "サーバー操作", "管理コマンドをUIから実行します");
        action(10, Items.WRITABLE_BOOK, "設定を再読込", () -> { ConfigManager.loadAll(); notice = "設定を再読込しました。"; }, "reload と同じ操作");
        action(12, Items.WITHER_SKELETON_SKULL, "緊急ボスを出現", () -> {
            boolean ok = BossEvent.spawnNow(owner.getServer());
            notice = ok ? "緊急ボスを出現させました。" : "出現できませんでした（プレイヤー不在 or 上限）。";
        }, "boss と同じ操作", "確率ロールをスキップ");
        boolean mc = FxSettings.get().marginCallEnabled;
        action(14, mc ? Items.REDSTONE_BLOCK : Items.GRAY_CONCRETE, "FX追証: " + (mc ? "あり" : "なし（ゼロカット）"),
                this::toggleMarginCall, "fx margincall と同じ操作", "クリックで切替・fx.jsonへ保存");
        action(16, Items.PAPER, "FX設定を再読込", () -> {
            boolean ok = FxSettings.load();
            notice = ok ? "FX設定を再読込しました。" : "FX設定の読込に失敗しました。";
        }, "fx reload と同じ操作");
        var symbols = FxSettings.get().symbols;
        if (!symbols.isEmpty()) {
            if (fxSymbol == null || symbols.stream().noneMatch(s -> s.id.equals(fxSymbol))) fxSymbol = symbols.get(0).id;
            final String sym = fxSymbol;
            action(28, Items.EMERALD, "FXショック対象: " + sym, this::cycleFxSymbol, "クリックで通貨を切替");
            action(30, Items.REDSTONE_BLOCK, "暴落させる (" + sym + ")", () -> shock(true), "fx shock " + sym + " crash と同じ操作");
            action(32, Items.EMERALD_BLOCK, "急騰させる (" + sym + ")", () -> shock(false), "fx shock " + sym + " rally と同じ操作");
        } else {
            set(28, Items.GRAY_DYE, "FX通貨が設定されていません");
        }
        action(49, Items.ARROW, "プレイヤー選択へ戻る", () -> view = "PLAYERS");
    }
    private void toggleMarginCall() {
        try {
            FxSettings.setMarginCall(!FxSettings.get().marginCallEnabled);
            notice = "FX追証を切り替えました。";
        } catch (java.io.IOException ex) {
            notice = "fx.jsonを保存できませんでした。変更は適用していません。";
        }
    }
    private void cycleFxSymbol() {
        var symbols = FxSettings.get().symbols;
        if (symbols.isEmpty()) return;
        int idx = 0;
        for (int i = 0; i < symbols.size(); i++) if (symbols.get(i).id.equals(fxSymbol)) { idx = i; break; }
        fxSymbol = symbols.get((idx + 1) % symbols.size()).id;
    }
    private void shock(boolean crash) {
        try {
            FxPersistentState fx = FxPersistentState.get(owner.getServer());
            fx.engine().shock(fxSymbol, crash);
            fx.setDirty();
            notice = fxSymbol + " を" + (crash ? "暴落" : "急騰") + "させました。";
        } catch (RuntimeException ex) {
            notice = "FXショックに失敗しました: " + ex.getMessage();
        }
    }
    private void beginAmount(String op) { operation = op; amount = 0; notice = ""; view = "AMOUNT"; }
    private void input() {
        boolean coinsOp = operation.equals("COINS_ADD") || operation.equals("COINS_SET");
        if (amount < 0) amount = 0;
        set(13, Items.GOLD_INGOT, "入力額: " + Numbers.gc(amount), label(), "数字頭と紙で調整", "最大 " + Numbers.gc(Integer.MAX_VALUE));
        // Digit heads (thousands/hundreds/tens/ones): left click +1, right click -1 (bank style).
        int[] places = {1000, 100, 10, 1};
        String[] labels = {"千の位", "百の位", "十の位", "一の位"};
        for (int i = 0; i < 4; i++) {
            final long place = places[i];
            long digit = (amount / place) % 10;
            actionStack(5 + i, GuildItems.digitHead((int) digit, "入力額", labels[i]), button -> {
                long cur = (amount / place) % 10;
                long next = Math.floorMod(cur + (button == 0 ? 1 : -1), 10);
                amount = Math.min(Integer.MAX_VALUE, Math.max(0, amount - cur * place + next * place));
            });
        }
        addPaper(14, 1);
        addPaper(15, 10);
        addPaper(16, 100);
        addPaper(17, 1000);
        action(45, Items.RED_DYE, "入力を消去", () -> amount = 0);
        action(48, Items.CHEST, coinsOp ? "現在の所持金を入力" : "現在の借入額を入力",
                () -> amount = Math.min(Integer.MAX_VALUE, coinsOp ? data().coins : data().loan));
        action(50, Items.LIME_CONCRETE, "内容を確認", () -> review(operation));
        action(53, Items.BARRIER, "戻る", () -> view = "DETAIL");
    }
    private void addPaper(int slot, int add) {
        long preview = Math.min(Integer.MAX_VALUE, amount + add);
        action(slot, Items.PAPER, "＋" + add, () -> amount = Math.min(Integer.MAX_VALUE, amount + add),
                "現在: " + Numbers.format(amount) + " → " + Numbers.format(preview));
    }
    private String label() {
        return switch (operation) {
            case "ADD" -> "借入に加算";
            case "SET" -> "借入を指定額へ変更";
            case "CLEAR" -> "借入を帳消し";
            case "COINS_ADD" -> "所持金に加算";
            case "COINS_SET" -> "所持金を指定額へ変更";
            case "ON" -> "滞納状態にする";
            default -> "滞納状態を解除";
        };
    }
    private void review(String op) { operation = op; reviewedLoan = data().loan; reviewedLock = data().delinquent; reviewedCoins = data().coins; view = "CONFIRM"; }
    private void confirmation() {
        boolean coinsOp = operation.equals("COINS_ADD") || operation.equals("COINS_SET");
        if (coinsOp) {
            long after = operation.equals("COINS_ADD")
                    ? (reviewedCoins > Long.MAX_VALUE - amount ? Long.MAX_VALUE : reviewedCoins + amount) : amount;
            set(22, Items.PAPER, "確認: " + label(), "対象: " + name(target), "所持金 " + Numbers.format(reviewedCoins) + " → " + Numbers.gc(after), "借入・債務は変更しません");
        } else {
            long after = switch (operation) {
                case "ADD" -> reviewedLoan > Long.MAX_VALUE - amount ? Long.MAX_VALUE : reviewedLoan + amount;
                case "SET" -> amount;
                case "CLEAR" -> 0;
                default -> reviewedLoan;
            };
            set(22, Items.PAPER, "確認: " + label(), "対象: " + name(target), "借入 " + Numbers.format(reviewedLoan) + " → " + Numbers.gc(after),
                    "滞納 " + (reviewedLock ? "あり" : "なし") + " → " + (operation.equals("ON") || !operation.equals("OFF") && reviewedLock ? "あり" : "なし"), "FX・リボ債務と所持金は変更しません");
        }
        action(29, Items.ARROW, "戻る", () -> view = coinsOp || operation.equals("ADD") || operation.equals("SET") ? "AMOUNT" : "DETAIL");
        action(33, Items.LIME_CONCRETE, "確定する", this::apply, "対象と変更内容を確認してください");
    }
    private void apply() {
        boolean coinsOp = operation.equals("COINS_ADD") || operation.equals("COINS_SET");
        if (data().loan != reviewedLoan || data().delinquent != reviewedLock || (coinsOp && data().coins != reviewedCoins)) {
            notice = "状態が変わりました。再度確認してください。"; view = "DETAIL"; return;
        }
        long due = System.currentTimeMillis() + ConfigManager.config().consumerLoanIntervalMinutes * 60_000L;
        switch (operation) {
            case "ADD" -> AdminLoanPolicy.add(data(), amount, due);
            case "SET" -> AdminLoanPolicy.set(data(), amount, due);
            case "CLEAR" -> AdminLoanPolicy.clear(data());
            case "ON" -> AdminLoanPolicy.delinquent(data(), true);
            case "OFF" -> AdminLoanPolicy.delinquent(data(), false);
            case "COINS_ADD" -> data().coins = data().coins > Long.MAX_VALUE - amount ? Long.MAX_VALUE : data().coins + amount;
            case "COINS_SET" -> data().coins = amount;
            default -> throw new IllegalStateException("Unknown admin operation");
        }
        state().setDirty();
        String result = name(target) + ": " + label() + " / 所持金 " + Numbers.gc(data().coins) + " / 借入 " + Numbers.gc(data().loan) + " / 滞納 " + (data().delinquent ? "あり" : "なし");
        owner.createCommandSourceStack().sendSuccess(() -> Component.literal("[Guild Admin] " + result), true);
        notice = "設定を反映しました。"; view = "DETAIL";
    }
    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (player != owner || owner.containerMenu != this || !allowed(owner)) { if (player == owner) owner.closeContainer(); return; }
        if (type != ClickType.PICKUP || (button != 0 && button != 1) || slot < 0 || slot >= 54) return;
        java.util.function.IntConsumer action = actions.get(slot); if (action == null) return;
        action.accept(button); if (owner.containerMenu == this) { populate(); broadcastChanges(); }
    }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return player == owner && allowed(owner) && !owner.isRemoved(); }
}
