# 筋ログ (Android)

広告なし・完全ローカル保存・AI評価つきの筋トレ記録アプリ。

## 構成
- Kotlin + Jetpack Compose + Room (SQLite)
- targetSdk 36（2026-08-31以降のPlay必須要件に対応済み）
- Auto Backup 有効：機種変更・再インストールでも記録が復元される（APIキーのみ除外）
- AI評価は TrainingEvaluator インターフェースで抽象化
  - OnDeviceEvaluator: Gemini Nano (ML Kit GenAI)。対応端末で維持費ゼロ・完全オフライン。
    実装時に https://developers.google.com/ml-kit/genai の最新手順を確認して依存を追加すること。
  - ByokGeminiEvaluator: ユーザー自身のGemini APIキーで動作（実装済み・動作可能）。
    非対応端末のフォールバック。開発者側の費用ゼロ。

## ビルド
1. Android Studio (Ladybug以降) で本フォルダを開く
2. Gradle Sync → 実機/エミュレータで Run
3. このコードはAndroid SDKなしの環境で書かれておりコンパイル未検証。
   Sync時の依存バージョン警告や小さな修正は発生し得る前提で扱うこと。

## リリース手順（Google Play）
1. Play Console 登録（$25・一回のみ）
2. 内部テスト → クローズドテスト（テスター12人×14日連続オプトイン。相互テストコミュニティ利用可）
3. 製品版アクセス申請 → 審査 → 公開
4. 投げ銭は Play Billing の消費型アイテムとして追加（フェーズ2）

## フェーズ2候補
- Gemini Nano 本実装（AICore対応判定＋フォールバック）
- 種目別の重量/e1RM推移グラフ
- Play Billing 投げ銭（コーヒー1杯 ¥300 消費型）
- プレート計算機、レスト タイマー
