# Guild Quests & Economy (NeoForge 1.21.1)

Minecraft 1.21.1 の NeoForge 向けに、クエスト、ギルド通貨、銀行、ギャンブルを単体で実装した Mod です。FTB Quests や Q Shop などへの依存はありません。

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
- リボ払い: 利息、延滞時のギルド機能ロック。既定では返済は採掘のみ（`miningDebtRepaymentOnly`を`false`にすると定期徴収・クエスト報酬充当も使える）
- 消費者金融: ギルドランクに応じた上限まで借り入れできる。既定では返済は採掘のみで、設定したブロックを掘ると借入を優先し、次にリボ債務を直接減らす（残高には入らない）
- 入金ブロック（ホッパー対応、コインを自動で残高へ換金）
- 銀行ブロック（手数料無料で銀行を開く）

### ギャンブル

- ブラックジャック（21 に近い方が勝ち、バースト、ナチュラルは 2.5 倍）
- 競馬（オッズと勝率を一致させ、期待値が 1 未満になるよう調整）
- チンチロリン、コイントス、ルーレット、スロット
- ランキング

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
- 強さが4段階のボスが出現する（弱 / 並 / 強 / 最強）。階層ごとにHP・装備・エンチャント・ポーション効果・報酬が変わる
  - 弱: HP 0.5倍・鉄装備・報酬 0.5倍
  - 並: HP 1.0倍・ダイヤ装備・報酬 1.0倍
  - 強: HP 2.0倍・強化ダイヤ装備・攻撃力/速度上昇・報酬 2.0倍
  - 最強: HP 4.0倍（バニラ上限の1024で頭打ち）・ネザライト装備・攻撃力II/速度上昇/耐性・報酬 4.0倍
  - 出現比率は `bossTierWeights`（既定 弱30 / 並40 / 強22 / 最強8）
- 階層は進行度で解禁される。プレイヤーごとに「オンラインで迎えたゲーム内日数」を記録し、出現時にオンライン勢の平均日数で上限を決める
  - 平均 5日未満はボスが出現しない
  - 平均 5日で弱、10日で並、20日で強、30日で最強が解禁（`bossTierUnlockDays`）
  - 解禁直後は強さ55%で出現し、次のティアが解禁されるまでに100%まで上がる（`bossTierRampStartPercent`）
  - 未解禁の階層は重みが 0 になる。最弱→最強の復活も最強の解禁後だけ発生する
  - `bossDifficultyProgression: false` にすると旧来どおり最初から全階層が出る
- 与えたダメージに応じて報酬を分配する（貢献度は防具・耐性・オーバーキルを除いた実際のHP減少量）
- 環境ダメージによる撃破は無効
- クライアント側にボス位置ピンを表示する
- 管理コマンド `/guildadmin boss [count]` で同時に複数体（最大32体）を出現できる。各ボスは個別のボスバー・ダメージ集計・報酬分配を持つ
- `/guildadmin boss progress` で現在の平均プレイ日数と解禁済みの階層を確認できる
- ボスに倒されると、復活後に **`guildquests:boss_protection`（緊急ボス保護）** が付与される
  - 効果中はボスがそのプレイヤーをターゲットせず、ダメージも通らない
  - ボスを攻撃するか、全ボスから `bossProtectionReleaseRange` ブロック以上離れると解除
  - 効果時間は `bossProtectionSeconds`（既定5分）

### 管理者向け

- 借入・滞納・コイン・ボスを操作できる管理 UI（引数なしの `/guildadmin`、またはギルド画面の「ギルド管理」）
- すべての管理コマンドを UI から実行できる。操作は [ADMIN.md](ADMIN.md) を参照

### その他

- プレイヤー別の永続化（コイン・クエスト進捗・累計達成数・日次カウンタ・借入・債務）
- 起動時に GitHub の最新リリースを確認し、新しいバージョンがあればタイトル画面で通知する（クライアント側の自動更新）。ダウンロードした JAR はリリースの SHA-256 と照合し、一致しない場合は適用しない
- G キーでクエスト GUI を開く
- Create 連携（ストックティッカーの支払いをギルドコインで自動決済する。シュート・ファンネルから入金ブロックへのコイン搬入にも対応）

## 必要環境

- Minecraft 1.21.1
- NeoForge 21.1.267（1.21.1）
- Java 21
- （任意）Create — ストックティッカー連携を使う場合

## ビルド

ビルドには JDK 21 と同梱の Gradle Wrapper を使います。生成した Mod の動作要件も Java 21 です。

```bash
./gradlew build
```

生成物:

```text
build/libs/guildquests_neoforge-1.21.1_26.17.3.jar
```

## 導入

1. NeoForge で Minecraft 1.21.1 を用意します。
2. 26.17.3 の JAR を `mods/` へ配置します。ワールドと設定を保存し、古い GuildQuests の JAR は重複させず置き換えます。
3. 一度起動すると `config/guildquests/` が作成されます。
4. 設定を変更したら、サーバーを再起動するか `/guildadmin reload` を実行します。

NeoForge版では、クエスト・銀行・送金・消費者金融・全ギャンブル・ランキング・オークション・管理・入金ブロック設定を専用のボタンGUIで操作します。背景のワールドを動かしたまま暗転して表示し、ウィンドウサイズとGUIスケールに応じて1〜3列へ並べ替えます。長い一覧は画面下の矢印、マウスホイール、PageUp/PageDownで切り替えます。

銀行・送金・出品・入札・管理の金額は、「入力」でテンキーを開き、画面の数字ボタンまたは上部のテキスト入力欄で指定します。キーボードでの編集・貼り付けに対応し、「確定」またはEnterで金額を反映します。取引はその後に各操作ボタンで実行します。入力中のEscは編集を取り消してテンキーを閉じます。銀行の送金・返済は「入出金」タブにまとまっています。サーバーとクライアントの両方に同じ改修版JARを導入してください。詳細は[GUI.md](GUI.md)を参照してください。

## コマンド

| コマンド | 説明 |
|---|---|
| `/guild` | クエスト GUI を開く（G キーでも可） |
| `/guild status` | ランク・コイン・累計達成・支払い方法・債務を表示する |
| `/guild bank` | 銀行 GUI を開く |
| `/guild auction` | オークション市場 GUI を開く |
| `/guildadmin` | 借入・滞納などの管理 UI を開く（OP Lv2） |
| `/guildadmin reload` | JSON 設定を再読込する（OP Lv2） |
| `/guildadmin boss [count] [weak\|normal\|strong\|elite]` | 緊急ボスを即時に出現させる（count省略時は1体、最大32体、階層を省略すると抽選、OP Lv2） |
| `/guildadmin boss info` | 生存中の緊急ボスの体力と現在のターゲットを表示する（OP Lv2） |
| `/guildadmin coins set\|add <player> <amount>` | コインを設定・加算する（OP Lv2） |
| `/guildadmin loan add\|set <player> <amount>` | 借入を加算・指定額に設定する（OP Lv2） |
| `/guildadmin loan clear <player>` | 借入を帳消しにする（OP Lv2） |
| `/guildadmin delinquent <player> on\|off` | 管理者による滞納ロックを設定・解除する（OP Lv2） |

## GUI 構成（サーバー内部の表示枠）

以下の枠数は状態同期に使う内部形式です。NeoForgeの画面上ではチェストのマス目や固定座標を使わず、内容をボタンと説明へ配置します。

| 画面 | サイズ | 内容 |
|---|---|---|
| クエスト | 9×6 | 掲示板・受注中のクエスト・銀行/送金/ギャンブル/オークション/管理へのボタン |
| 銀行 | 9×3 | 預入/引出・数字頭での金額指定・支払い方法の切替・債務表示 |
| 送金 | 9×3 | オンラインプレイヤーから宛先を選び、金額を指定して送信する |
| ギャンブル | 9×3 | 各ギャンブルへのハブ |
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
config/guildquests/server-update.json  # NeoForge 専用サーバー
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
- `bossEventHealth`: ボスの体力（「並」の基準値。階層ごとに 0.5〜4.0 倍）
- `bossTierWeights`: ボス階層の出現重み `[弱, 並, 強, 最強]`（既定 `[30, 40, 22, 8]`）
- `bossDifficultyProgression`: オンライン勢の平均プレイ日数で階層を解禁する（既定 `true`）
- `bossTierUnlockDays`: 階層が解禁される平均プレイ日数 `[弱, 並, 強, 最強]`（既定 `[5, 10, 20, 30]`。先頭未満はボス不出現）
- `bossTierRampStartPercent`: 解禁直後の強さ（既定 `55`%。次のティア解禁までに100%へ上昇）
- `bossProtectionSeconds`: 死亡時に付与する緊急ボス保護の秒数（既定300、0で無効）
- `bossProtectionReleaseRange`: ボスからこの距離以上離れると保護を解除（既定48ブロック）
- `bankFeePercent`: 銀行の手数料（%）
- `riboInstallment`: リボの1回あたりの徴収額
- `riboInterestPercent`: リボの周期利息（%）
- `riboIntervalMinutes`: リボの徴収間隔（分）
- `consumerLoanEnabled`: 消費者金融の ON/OFF
- `consumerLoanInterestPercent`: 消費者金融の利息（%）
- `consumerLoanIntervalMinutes`: 消費者金融の利息を付ける間隔（分）
- `consumerLoanLimits`: ランクごとの借入上限
- `miningDebtRepaymentOnly`: `true`なら返済は採掘のみ。`false`なら定期引落・クエスト報酬充当も使える
- `loanMiningValues`: 採掘返済で使うブロックごとの換金額。既定は石・丸石 0.01、石炭鉱石 0.2、鉄鉱石 0.3、金鉱石 0.8、ダイヤ鉱石 2.0 GC
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

## サーバーの自動更新（NeoForge）

専用サーバーで GitHub Releases を定期確認し、最初の検出から設定した時間を待って更新します。
ダウンロードした JAR はリリースの SHA-256 と照合し、一致しない場合やダイジェストがない場合は適用しません。
設定は `config/guildquests/server-update.json` に自動生成されます。初期値は 30 分ごとの確認と、検出後の 30 分待機です。
`/guildadmin reload` または管理 GUI の設定再読込で変更を反映できます。

自動で起動し直すには、付属の `start-server.ps1` / `start-server.sh` から起動します。NeoForge の引数ファイルによる起動に対応しています。

設定の全項目と、ローダー別の導入・起動・復旧手順は [SERVER_UPDATE.md](SERVER_UPDATE.md) を参照してください。
