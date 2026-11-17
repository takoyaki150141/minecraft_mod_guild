package dev.guildquests.gui;

import java.util.List;

/** Stable view identities and semantic sections, independent of screen coordinates. */
public enum GuildMenuKind {
    GUILD(6, "本日のクエスト", "受注中", "各種メニュー", "ギルド情報"),
    BANK(3, "入出金", "支払い方法"),
    TRANSFER(3, "送金先", "金額・送金"),
    GAMBLE(3, "ゲームを選ぶ"),
    LOAN(3, "借入", "返済", "借入状況"),
    BLACKJACK(3, "プレイ"), CHINCHIRORIN(3, "プレイ"),
    COIN_FLIP(3, "プレイ"), ROULETTE(3, "プレイ"), SLOT(3, "プレイ"),
    HORSE_RACE(6, "レース", "馬・賭け金"),
    RANKING(3, "ランキング"),
    AUCTION_BROWSE(6, "出品一覧", "入札額", "出品・受取・案内"),
    AUCTION_SELL(3, "出品内容"),
    AUCTION_MINE(6, "自分の出品", "受取ボックス", "案内・一括受取"),
    ADMIN(6, "管理"), DEPOSIT_SETTINGS(1, "入金ブロック設定");

    public final int rows;
    public final List<String> sections;
    GuildMenuKind(int rows, String... sections) { this.rows = rows; this.sections = List.of(sections); }
    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }

    public int section(int slot) {
        return switch (this) {
            case GUILD -> slot >= 9 && slot <= 17 || slot == 4 ? 0
                    : slot >= 36 && slot <= 44 || slot == 6 ? 1 : slot <= 2 ? 3 : 2;
            case BANK -> slot >= 18 && slot <= 20 ? 1 : 0;
            case TRANSFER -> slot % 9 < 4 || slot == 23 || slot == 24 ? 0 : 1;
            case LOAN -> slot >= 9 && slot <= 12 ? 0 : slot >= 13 && slot <= 17 ? 1 : 2;
            case HORSE_RACE -> slot < 36 || slot >= 45 && slot <= 52 ? 0 : 1;
            case AUCTION_BROWSE -> slot >= 9 && slot <= 44 || slot == 4 || slot == 5 || slot == 6 ? 0 : slot >= 45 ? 1 : 2;
            case AUCTION_MINE -> slot >= 9 && slot < 27 ? 0 : slot >= 27 ? 1 : 2;
            default -> 0;
        };
    }

    /** The old digit-head controls are replaced by one validated text input. */
    public boolean digitSlot(int slot) {
        return switch (this) {
            case BANK, TRANSFER, AUCTION_SELL, ADMIN -> slot >= 5 && slot <= 8;
            case AUCTION_BROWSE -> slot >= 45 && slot <= 48;
            default -> false;
        };
    }
}
