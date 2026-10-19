# Guild Quests & Economy (Fabric / Forge 1.20.1)

Minecraft 1.20.1 の Fabric / Forge 向けに、クエスト、ギルド通貨、銀行、ギャンブルを単体で実装した Mod です。FTB Quests や Q Shop などへの依存はありません。

現在のバージョン: **26.12.1**

## 主な機能

### クエスト

- 日次クエスト掲示板（毎日自動で抽選、1〜9問）
- 討伐（KILL）: 指定した Mob を一定数倒す
- 納品（SUBMIT）: 指定アイテムを消費して納品する
- 収集（COLLECT）: 指定アイテムを納品して達成する。プレイヤーごとに設定した日数（既定は2日）のクールダウンがあり、その間は掲示板に表示されず受注もできない
- 探索（EXPLORE）
  - 座標と半径（ランダム座標に対応）
  - バイオーム（複数指定とランダム選択に対応）
  - 構造物
- 累計達成数によるランク（E〜S）とクエストの解放

### 通貨と経済

- アイテムではないギルドコイン
- 銀行（預け入れ・引き出し、手数料あり）
- プレイヤー間の送金（手数料無料）
- 支払い方法: 引き落とし / リボ（後払い）/ 無効
- リボ払い: 定期的な自動徴収、利息、延滞時のギルド機能ロック、クエスト報酬による自動返済
- 消費者金融: ギルドランクに応じた上限まで借り入れ、GUI から手動で返済する。返済が残っている間は、設定したブロックを採掘すると債務が直接減る（残高には入らない）
- 入金ブロック（ホッパー対応、コインを自動で残高へ換金）
- 銀行ブロック（手数料無料で銀行を開く）

### ギャンブル

- ブラックジャック（21 に近い方が勝ち、バースト、ナチュラルは 2.5 倍）
- 競馬（オッズと勝率を一致させ、期待値が 1 未満になるよう調整）
- チンチロリン、コイントス、ルーレット、スロット
- ランキング

### Guild FX

- Mod 専用の仮想通貨（AUR / EMB / TID）の取引所
- 成行・指値・逆指値・OCO・IFD・IFO、最大 1000 倍、TP/SL・トレーリング・部分決済に対応
- 相場はサーバー内部で生成し、外部の価格 API は使わない
- 既定はゼロカット。追証を有効にすると不足分を所持コインから精算し、未払いは既存の債務・滞納処理へ引き継ぐ
- 操作と設定は [FX.md](FX.md) を参照

### オークション（プレイヤー間マーケット）

- サーバー側でアイテムをエスクロー保管するグローバル GUI マーケット
- 出品時に「固定価格」または「入札式オークション」を選べる
- 固定価格: 買い手が即時に購入する
- 入札式: 開始価格から入札し、期限到来時に最高入札者が落札する
- 入札は残高から即時に引き落とし、更新時は前の入札者へ自動で返金する
- 落札品・期限切れ品・取消品は「受取ボックス」に届き、GUI から受け取る
- 売却成立時に、出品者へ手数料を差し引いて残高へ入金する
- 出品一覧はページ送りに対応し、自分の出品はキャンセルできる

### 緊急ボスイベント

- プレイヤーがオンラインのとき、一定間隔で確率発生する
- 強化したホスト Mob が出現する（グラウ効果とダイヤ装備）
- 与えたダメージに応じて報酬を分配する
- 攻撃力・最大HP・防具・効果と実際のダメージから、参加者ごとに難易度を調整する
- ボス戦後の復活には20秒の保護が付く（ボスへの攻撃で解除）
- 環境ダメージによる撃破は無効
- クライアント側にボス位置ピンを表示する

調整方法と検証手順は [BOSS_DIFFICULTY.md](BOSS_DIFFICULTY.md) を参照してください。

### 管理者向け

- 借入・滞納・コイン・ボス・FX を操作できる管理チェスト UI（引数なしの `/guildadmin`、またはギルド画面の「ギルド管理」）
- すべての管理コマンドを UI から実行できる。操作は [ADMIN.md](ADMIN.md) を参照

### その他

- プレイヤー別の永続化（コイン・クエスト進捗・累計達成数・日次カウンタ・借入・債務）
- 起動時に GitHub の最新リリースを確認し、新しいバージョンがあればタイトル画面で通知する（クライアント側の自動更新）
- G キーでクエスト GUI を開く
- Create 連携（ストックティッカーの支払いをギルドコインで自動決済する）

## 必要環境

- Minecraft 1.20.1
- Fabric: Fabric Loader 0.16.10 以上、Fabric API 0.92.x+1.20.1
- Forge: Forge 47.2.0（1.20.1）
- Java 17
- （任意）Create — ストックティッカー連携を使う場合

## ビルド

ビルドには JDK 21 と同梱の Gradle Wrapper を使います。生成した Mod の動作要件は Java 17 です。

```bash
./gradlew :common:test :fabric:build :forge:build
```

生成物:

```text
fabric/build/libs/guildquests_fabric-1.20.1_26.12.1.jar
forge/build/libs/guildquests_forge-1.20.1_26.12.1.jar
```

## 導入

1. Fabric または Forge で Minecraft 1.20.1 を用意します。
2. ローダーに合う 26.12.1 の JAR を `mods/` へ配置します。Fabric では Fabric API も必要です。ワールドと設定を保存し、古い GuildQuests の JAR は重複させず置き換えます。
3. 一度起動すると `config/guildquests/` が作成されます。
4. 設定を変更したら、サーバーを再起動するか `/guildadmin reload` を実行します。

クエストや銀行は Minecraft 標準のチェスト画面、Guild FX は専用のクライアント画面を使います。

## コマンド

| コマンド | 説明 |
|---|---|
| `/guild` | クエスト GUI を開く（G キーでも可） |
| `/guild status` | ランク・コイン・累計達成・支払い方法・債務を表示する |
| `/guild bank` | 銀行 GUI を開く |
| `/guild auction` | オークション市場 GUI を開く |
| `/guild fx` | Guild FX を開く（F8 は任意で有効化） |
| `/guildadmin` | 借入・滞納などの管理チェスト UI を開く（OP Lv2） |
| `/guildadmin reload` | JSON 設定を再読込する（OP Lv2） |
| `/guildadmin boss` | 緊急ボスを即時に出現させる（OP Lv2） |
| `/guildadmin coins set\|add <player> <amount>` | コインを設定・加算する（OP Lv2） |
| `/guildadmin loan add\|set <player> <amount>` | 借入を加算・指定額に設定する（OP Lv2） |
| `/guildadmin loan clear <player>` | 借入を帳消しにする（OP Lv2） |
| `/guildadmin delinquent <player> on\|off` | 管理者による滞納ロックを設定・解除する（OP Lv2） |
| `/guildadmin fx margincall [true\|false]` | 追証の確認・変更・保存をする（OP Lv2） |
| `/guildadmin fx reload` | FX 設定を再読込する（OP Lv2） |

## GUI 構成

| 画面 | サイズ | 内容 |
|---|---|---|
| クエスト | 9×6 | 掲示板・受注中のクエスト・銀行/送金/ギャンブル/オークション/管理へのボタン |
| 銀行 | 9×3 | 預入/引出・数字頭での金額指定・支払い方法の切替・債務表示 |
| 送金 | 9×3 | オンラインプレイヤーから宛先を選び、金額を指定して送信する |
| ギャンブル | 9×3 | 各ギャンブルと Guild FX へのハブ |
| ギルド管理 | 9×6 | プレイヤー選択・借入/コイン/滞納の操作・数値入力・変更確認 |
| ブラックジャック | 9×3 | ヒット/スタンド・賭け金の設定 |
| 競馬 | 9×6 | 馬の選択・賭け・レーン走行の演出・結果表示 |
| オークション | 9×6 | 出品一覧（ページ送り）・購入・入札額の設定 |
| 出品 | 9×3 | 手に持ったアイテムの形式と価格を設定して出品する |
| 自分の出品/受取 | 9×6 | 出品の取消・受取ボックスからの受け取り |
| 入金ブロック設定 | 9×1 | 通知の切替・一括入金 |

## 設定ファイル

初回起動時に `config/guildquests/` へ自動生成されます。

```text
config/guildquests/config.json
config/guildquests/quests.json
config/guildquests/server-update.json  # Fabric / Forge 専用サーバー
```

### config.json

主な項目:

- `maxActiveQuests`: 同時受注数（1〜9）
- `dailyQuestCountMin` / `dailyQuestCountMax`: 日次掲示板の出題数範囲
- `exploreCheckIntervalTicks`: 探索の判定間隔（既定は 20）
- `exploreRandomRange`: ランダム座標の探索範囲
- `bossEventEnabled`: 緊急ボスイベントの ON/OFF
- `bossEventIntervalMinutes`: ボス出現の判定間隔（分）
- `bossEventChancePercent`: ボス出現の確率（%）
- `bossEventReward`: ボス撃破の報酬（コイン）
- `bossEventHealth`: ボスの体力
- `bossAdaptiveDifficultyEnabled`: プレイヤーごとの難易度調整（既定は `true`）
- `bossTargetPlayerHits`: 単独討伐に必要な攻撃回数の基準（既定は80）
- `bossPlayerSurvivalHits`: 防具なしでボスの標準攻撃を受ける回数の基準（既定は6）
- `bossRespawnProtectionSeconds`: ボス戦後の復活保護の秒数（既定は20、0で無効）
- `bankFeePercent`: 銀行の手数料（%）
- `riboInstallment`: リボの1回あたりの徴収額
- `riboInterestPercent`: リボの周期利息（%）
- `riboIntervalMinutes`: リボの徴収間隔（分）
- `consumerLoanEnabled`: 消費者金融の ON/OFF
- `consumerLoanInterestPercent`: 消費者金融の利息（%）
- `consumerLoanIntervalMinutes`: 消費者金融の利息を付ける間隔（分）
- `consumerLoanLimits`: ランクごとの借入上限
- `loanMiningValues`: 採掘返済で使うブロックごとの換金額
- `auctionFeePercent`: オークション売却時の手数料（%）
- `auctionDurationMinutes`: 出品が期限切れになるまでの時間（分）
- `auctionMaxListingsPerPlayer`: 1人あたりの同時出品上限
- `auctionBidStep`: 入札時に上乗せが必要な最低単位
- `rankThresholds`: ランク名と累計達成数の閾値
- `createIntegrationEnabled`: Create 連携の ON/OFF

### quests.json

共通項目:

```json
{
  "id": "unique_id",
  "title": "表示名",
  "description": "説明",
  "type": "KILL",
  "target": "minecraft:zombie",
  "targets": ["minecraft:zombie", "minecraft:skeleton"],
  "amount": 20,
  "amountMin": 10,
  "amountMax": 30,
  "reward": 30,
  "requiredCompleted": 0,
  "repeatable": true
}
```

`targets` を指定すると抽選時にランダムで選ばれます。`amountMin` と `amountMax` も同じです。

#### KILL

- `target` / `targets`: Entity Type ID
- `amount`: 討伐数

#### SUBMIT

- `target`: Item ID
- `amount`: 納品数
- GUI の受注中クエストを左クリックすると、所持分から必要数まで消費します。

#### COLLECT

```json
{
  "type": "COLLECT",
  "target": "minecraft:iron_ingot",
  "amount": 32,
  "amountMin": 16,
  "amountMax": 48,
  "reward": 8,
  "repeatable": true,
  "cooldownDays": 2
}
```

`cooldownDays` は受取の間隔（ゲーム内の日数）です。この間は掲示板に表示されず受注もできません。

#### EXPLORE / COORDINATE

```json
{
  "type": "EXPLORE",
  "exploreMode": "COORDINATE",
  "dimension": "minecraft:overworld",
  "x": 1000,
  "y": 64,
  "z": 1000,
  "radius": 32,
  "randomCoordinates": true,
  "coordRange": 3000
}
```

`randomCoordinates` を true にすると、`coordRange` の範囲内で座標が抽選されます。

#### EXPLORE / BIOME

```json
{
  "type": "EXPLORE",
  "exploreMode": "BIOME",
  "target": "minecraft:desert",
  "biomes": ["minecraft:desert", "minecraft:badlands"]
}
```

#### EXPLORE / STRUCTURE

```json
{
  "type": "EXPLORE",
  "exploreMode": "STRUCTURE",
  "target": "minecraft:fortress"
}
```

STRUCTURE は、プレイヤーの現在座標が対象構造物の内部に入った時点で達成になります。

## データ保存

プレイヤーデータは Overworld の PersistentState に保存され、ワールドの `data/guildquests_players.dat` として永続化されます。UUID をキーにしているため、ログアウト/再ログインやサーバー再起動のあとも維持されます。

## 補足

- 討伐の判定は、プレイヤー自身が直接倒した Entity を対象にします。
- リボで延滞すると、銀行・送金・入金ブロックがロックされます。クエスト報酬は自動で債務の返済に充てられます。オークションの購入・入札も延滞中はロックされます（出品は可能です）。
- オークションの出品アイテムはサーバー側（ワールドデータ）にエスクロー保存されます。落札品と返却品は、各プレイヤーの受取ボックスに届きます。
- ボスの環境ダメージ（溶岩・落下など）による撃破は無効です。対策として、落下保護と脱出テレポートが入っています。
- 友人のリポジトリへの PR 手順と記入例は [PR_GUIDE.md](PR_GUIDE.md) にあります。

## サーバーの自動更新（Fabric / Forge）

専用サーバーで GitHub Releases を定期確認し、最初の検出から設定した時間を待って更新します。
設定は `config/guildquests/server-update.json` に自動生成されます。初期値は 30 分ごとの確認と、検出後の 30 分待機です。
`/guildadmin reload` または管理 GUI の設定再読込で変更を反映できます。

自動で起動し直すには、付属の `start-server.ps1` / `start-server.sh` から起動します。Fabric と Forge を自動判定し、Forge の引数ファイルによる起動にも対応しています。

設定の全項目と、ローダー別の導入・起動・復旧手順は [SERVER_UPDATE.md](SERVER_UPDATE.md) を参照してください。
