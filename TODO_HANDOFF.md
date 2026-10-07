# Entity 303 — מסמך העברה (מה נעשה, מה נשאר)

מוד Fabric למיינקראפט **1.21.11** שמוסיף את **Entity 303** כבוס. המסמך הזה נכתב כשעצרו אותי באמצע העבודה,
כדי שאפשר יהיה להמשיך במחשב אחר. **קרא אותו מההתחלה עד הסוף לפני שנוגעים בקוד.**

---------------------------------------------------------------------------------------------------

## 1. המצב בשורה אחת

* הגרסה האחרונה שנבנתה ונבדקה **במשחק** על ידך: `last_working_jar/entity-303-1.0.0.jar`
  (1000 חיים, נזק ×2, בלי השינויים שבסעיף 3).
* עץ העבודה (הקוד שבתיקייה הזאת) **מתקמפל בהצלחה** (נבדק: `BUILD SUCCESSFUL`),
  אבל **עוד לא נבנה ממנו jar, ולא הורץ במשחק בכלל**.
* חסרים 3 קבצי נתונים (סעיף 4, משימה 1) כדי שמכת ה‑"50% חיים" תעקוף שריון באמת.

---------------------------------------------------------------------------------------------------

## 2. מה היה ועובד (נבדק במשחק)

* ישות `entity303:entity_303`, ביצת ספאון בלשונית Spawn Eggs, `/summon entity303:entity_303`.
* 1000 חיים, נזק ×2 (מוכפל ב‑`DAMAGE_SCALE` בקובץ `Entity303.java`, ובשלב 2 ×1.25 ובשלב 3 ×1.5 מעל זה).
* בוס בר עם 20 חלוקות; הצבע והכותרת משתנים לפי שלב (לבן → סגול "ENRAGED" → אדום מהבהב "FINAL FORM").
* 3 שלבים (66% / 33% חיים), שאגה + גל הדף בכל מעבר שלב.
* התקפות: Sweep, Soul Slash, Shadow Step, Summon, Soul Drain, Whirlwind (הישנה), Reaper's Wrath (Slam), Roar.
* הסקין האמיתי שלך (`skin/entity303_skin.png`) על מודל בפריסת סקין סטנדרטית + מגל שנבנה ב‑Blender.
* שלל: 2 נחש (nether star), יהלומים, נתריט, טוטם, תפוחי זהב מכושפים, 500 XP.

---------------------------------------------------------------------------------------------------

## 3. מה נוסף בעץ העבודה ועדיין **לא נבדק במשחק**

הבקשות האחרונות שלך, כולן כתובות בקוד ומתקמפלות:

1. **הבוס מרגיש כמה שחקנים יש סביבו** (`Entity303AttackGoal.java`, רדיוס `AWARE_RADIUS = 40`):
   * Soul Slash יורה קרן על **כל** שחקן בטווח (עד 8), לא רק על המטרה.
   * Soul Drain מנקז כמה שחקנים בבת אחת (עד `1 + שלב (+1 אם 4+ שחקנים)`), ההחלמה שלו לא גדלה באותו יחס.
   * Summon: יותר vexes ככל שיש יותר שחקנים (`1 + שלב + (שחקנים-1)`), תקרה `6 + 2*(שחקנים-1)` עד 16,
     והוא מפזר את ה‑vexes על שחקנים שונים (פעם ב‑20 טיקים).
   * Slam מסמן מקום מתחת לכל שחקן (עד 10) + נקודות נוספות לפי כמות השחקנים.
   * Shadow Step קופץ לשחקן אקראי (לא תמיד לאותו אחד), והוא מחליף מטרה כל 100–200 טיקים.
   * ההפסקה בין התקפות מתקצרת עם יותר שחקנים (`1/(1+0.12*(n-1))`), ובחירת ההתקפה מתחשבת בכמות ובמרחק השחקנים.
2. **המגל כבר לא עובר דרך הגוף כשהוא מסתובב.**
   * הסיבוב הוא עכשיו "גלגל" מול הגוף (מצב `WHEEL` ב‑`tools/animations.py`: `scythe=[0,0,90]`, הסיבוב סביב ציר היד).
   * `tools/collide.py` בודק התנגשויות מדויקות (תיבות מסתובבות, SAT) בין כל קוביית מגל לכל קוביית גוף,
     בכל טיק של כל אנימציה ומכמה זוויות התחלה — **התוצאה הסופית: clean בכל האנימציות**
     (חדירה לרצפה עד 0.2 פיקסל = 2 ס"מ).
   * `tools/autofix.py` מוסיף אוטומטית מפתחות תיקון (`tools/animations_fixed.py`, נוצר אוטומטית — לא לערוך ידנית).
   * המגל הוקטן ל‑0.7 מהמודל המקורי (`SCYTHE_SCALE` ב‑`tools/gen_assets.py`).
   * במודל (`Entity303Model.java`) הסיבוב מוכפל ב"משקל גלגל" כדי שמגל שמוחזק מהצד (במכות) לא יסתובב.
3. **מכת המערבולת הוחלפה ל‑"Reaper's Descent"** (בקוד: `WHIRL`, הפונקציה `descent` ב‑`Entity303AttackGoal`):
   * שלב 2 ומעלה, רק אם יש מעליו לפחות 5 בלוקים של מקום.
   * הוא **עף 7 בלוקים למעלה** ומסתובב (טיקים 0–16 עלייה, 16–72 סיבוב ומשיכה).
   * כל השחקנים (עד 26 בלוקים) נמשכים אליו ו"מודבקים לריצפה" (אין קפיצה, מהירות משיכה 0.16–0.42 בלוקים לטיק).
   * טיקים 72–80 הוא מתכונן (צליל + חלקיקים), 80–88 צלילה, **טיק 88 נחיתה**.
   * בנחיתה, כל מי שבתוך 8 בלוקים מקבל **50% מהחיים המקסימליים שלו** (`DESCENT_FRACTION = 0.5`),
     נזק שאמור לעקוף שריון, כישופים, אפקטים וחסימה במגן (ראו משימה 1), ונזרק אחורה.
   * הוא לא סובל מנזק נפילה (`hurtServer` מתעלם מ‑`IS_FALL`), ויש רשת ביטחון שמחזירה לו כוח משיכה.
   * אנימציה: `tools/animations.py` → `"whirl"` (משך 104 טיקים, אירועים: rise=16, pull_end=72, dive=80, impact=88).
4. **שריון ×2:** `Entity303.ARMOR = 16.0` (היה 8). פירשתי את ההודעה שלך כ"פי שניים שריון". אם התכוונת למשהו אחר — תתקן.

---------------------------------------------------------------------------------------------------

## 3ב. עדכון (אוקטובר 2026): הבוס נבדק במשחק ואושר, והתווספו:

* **משימה 1 (קבצי הנתונים של Reaper's Descent) בוצעה** (`damage_type/reaper_descent.json`, שש תגיות `bypasses_*`, הודעות מוות) ו‑`mod_version` = 1.1.0.
* **שלב 3 ("FINAL FORM"):** חסין לכל אפקט (טוב או רע, גם שיקויים: `canBeAffected` + `isAffectedByPotions` ב‑`Entity303.java`),
  כל ההתקפות פי 1.5 נזק (`FINAL_DAMAGE_BONUS`, הורד מ‑2.0; הפירוש שלי ל"תוקף פי שתיים") וגונב 50% מהחיים שהוא לוקח משחקנים
  (`LIFESTEAL_FRACTION`; כל הנזק עובר דרך `damage()` ב‑`Entity303AttackGoal`; ה‑Drain לא מרפא בנוסף בשלב 3).
  ה‑Descent נשאר 50% מהחיים המקסימליים (לא מוכפל).
* **12 אנימציות חדשות** (הוגדרו ב‑`tools/animations.py`, תזמון בצד השרת ב‑`Entity303AttackGoal`):
  `combo`, `lunge`, `leap`, `hook`, `rings`, `dance`, `guard`, `rain` (התקפות), `spawn` (יוצא מהאדמה בעת ההזמנה, `finalizeSpawn`),
  `death` (נופל על הגב; הוא נשאר `DEATH_TICKS` במקום 20 טיקים), `taunt`, `idle_rage` (עמידה של שלב 3).
* **רגליים באנימציות:** `right_leg`/`left_leg` נוספו לסוף `PARTS` (מספרי הערוצים הישנים לא השתנו).
* הכלים: `autofix.py` רץ עכשיו במקביל על כל הליבות, לולאות (`loop=True`) נבדקות בכל זווית של הסיבוב,
  ו‑`NO_GROUND_CHECK` (spawn, death) מדלג על בדיקת הרצפה.
* על המחשב הזה אין Python בנתיב. הרצתי את הכלים עם `C:\Users\playe\AppData\Roaming\uv\python\cpython-3.14.0-windows-x86_64-none\python.exe`
  (בלי Pillow, לכן `gen_assets.py` המלא לא רץ; רק החלק שמייצר את `Entity303Animations.java`, ראו `tools/gen_anims_only.py`).
* **לא נבדק במשחק:** כל מה שבסעיף 3ב. ה‑jar האחרון שנבדק במשחק הוא `last_working_jar` (1.0.0).

---------------------------------------------------------------------------------------------------

## 4. מה צריך לעשות (לפי סדר)

> משימות 1–2 בוצעו (ראו 3ב). משימה 3 (בדיקה במשחק) פתוחה, כולל כל ההתקפות החדשות.

### משימה 1 (חובה) — קבצי הנתונים של "Reaper's Descent"
בלעדיהם הקוד נופל חזרה להתקפת מוב רגילה (שריון *כן* מפחית אותה). צריך ליצור:

`src/main/resources/data/entity303/damage_type/reaper_descent.json`
```json
{
	"exhaustion": 0.0,
	"message_id": "entity303.reaper_descent",
	"scaling": "never"
}
```

ושישה קבצי תגיות (כולם עם אותו תוכן) בתיקייה `src/main/resources/data/minecraft/tags/damage_type/`:
`bypasses_armor.json`, `bypasses_effects.json`, `bypasses_enchantments.json`, `bypasses_resistance.json`,
`bypasses_shield.json`, `bypasses_cooldown.json`
```json
{
	"values": [
		"entity303:reaper_descent"
	]
}
```
(מתמזג עם התגיות של מיינקראפט, לא מחליף אותן.)

ב‑`src/main/resources/assets/entity303/lang/en_us.json` להוסיף הודעות מוות:
```json
"death.attack.entity303.reaper_descent": "%1$s was crushed by the descent of Entity 303",
"death.attack.entity303.reaper_descent.player": "%1$s was crushed by %2$s's descent"
```
**החלטה פתוחה:** אני לא הוספתי `bypasses_invulnerability` בכוונה — הוא גם משבית טוטם של אל‑מוות
(טוטם עדיין יציל שחקן). ספיגת נזק (absorption, תפוחי זהב) תמשיך להפחית. אם "לא משנה כלום" אצלך כולל גם אותם — צריך לטפל.

### משימה 2 — לבנות ולבדוק
```
./gradlew build          # Windows: gradlew.bat build   (דורש JDK 21)
```
ה‑jar ב‑`build/libs/entity-303-1.0.0.jar` (לא ה‑`-sources`). להעלות את `mod_version` ב‑`gradle.properties`
ל‑1.1.0 לפני שמפיצים.

### משימה 3 — בדיקה במשחק (עוד לא נעשתה בכלל!)
צריך Fabric Loader ≥ 0.18, Fabric API `0.141.x+1.21.11`, מצב survival (הוא מתעלם מקריאייטיב וספקטייטור).
* `/summon entity303:entity_303`
* לקפוץ לשלבים מהר: `/data modify entity @e[type=entity303:entity_303,limit=1] Health set value 600.0f` (שלב 2),
  `300.0f` (שלב 3).
* לבדוק: הבוס בר, ההתקפות אחת אחת, **ה‑Descent** (עלייה, משיכה, הדבקה לריצפה, נחיתה, 50% חיים, טוטם עובד,
  שריון לא מפחית), בדיקה עם **2+ שחקנים** (קרניים מרובות, ניקוז מרובה, vexes על שחקנים שונים),
  מקומות עם תקרה נמוכה (הוא אמור לא לבחור ב‑Descent), מים, מערות.
* אם יש קריסה/באג: `logs/latest.log` (או הקונסול של `./gradlew runClient`).

### משימה 4 — לבדוק שהאנימציות נראות טוב
`tools/autofix.py` הוסיף כ‑40 מפתחות תיקון אוטומטיים. הם מבטיחים שאין חדירה, אבל **לא נבדקו ויזואלית** —
ייתכן ריצוד או תנועה מוזרה בחלק מהמעברים.
```
blender -b -P blender/preview_entity.py -- --view front        # אפשר גם --view side
python tools/sheet.py preview/frames preview/sheet.png 6
```
אפשר לרנדר טיקים ספציפיים: `... -- sweep:2 sweep:3 whirl:76` (ראו תחילת הקובץ `preview_entity.py`).

### משימה 5 — איזון ומספרים (שיקול דעת שלך)
* נזק: `DAMAGE_SCALE` ב‑`Entity303.java`; מספרי כל התקפה ב‑`Entity303AttackGoal.java`.
  שים לב: Soul Drain הוא נזק קסם שעוקף שריון, ולכן ההכפלה כואבת בו יותר.
* "50% חיים" הוא ממקסימום החיים (לא מהחיים הנוכחיים) — שחקן עם פחות מחצי חיים ימות (אלא אם יש טוטם).
  להחליף ל"נוכחיים": ב‑`descentHit` להחליף `getMaxHealth()` ב‑`getHealth()`.
* רדיוסים של Descent: `PULL_RADIUS`, `HOVER_HEIGHT`, `IMPACT_RADIUS`.

### רעיונות להמשך (לא חובה)
* קובץ הגדרות (חיים/נזק/שריון) במקום קבועים בקוד · שחרור מגל כפריט נשק שהבוס מפיל · צלילים מותאמים
* ספאון טבעי · קובץ שפה בעברית (`he_il.json`) · בוס בר עם טקסטורה מותאמת (דורש resource pack, בוס בר רגיל לא ניתן לשינוי)

---------------------------------------------------------------------------------------------------

## 5. הקמה במחשב אחר

1. **JDK 21** (ב‑`JAVA_HOME` או ב‑PATH). Loom ננעל ל‑`1.17.21` ב‑`gradle.properties` כי `1.18+` דורש Java 25.
2. לחלץ את ה‑zip ל**נתיב קצר** (למשל `C:\dev\entity303`). נתיבים ארוכים (מעל ~200 תווים) שוברים כלים בווינדוס.
   אם בכל זאת, להגדיר `ENTITY303_ROOT=C:/נתיב/קצר` לפני הרצת סקריפטי הפייתון/Blender.
3. `gradlew build` הראשון מוריד Gradle 9.7.1, מיינקראפט, Loom, Fabric API (כמה מאות MB, כמה דקות).
4. סקריפטים בפייתון (רק אם משנים נכסים): Python 3.10+ ו‑`pip install pillow`.
5. Blender 5.x (רק למגל ולתצוגה מקדימה): `blender.exe` בדרך כלל ב‑`C:\Program Files\Blender Foundation\Blender 5.x\`.
6. בדיקה מהירה בלי משגר: `./gradlew runClient` (מוריד גם נכסי משחק, ~מאות MB).

## 6. פקודות

| מה | פקודה |
|---|---|
| בניית המוד | `./gradlew build` |
| לקמפל בלבד (מהיר) | `./gradlew compileJava compileClientJava` |
| לייצר מחדש טקסטורות/מודל/אנימציות | `python tools/build_assets.py` (מריץ autofix ואז gen_assets) |
| בדיקת התנגשויות מגל↔גוף | `python tools/collide.py` (חייב להדפיס clean בכל השורות) |
| המגל ב‑Blender | `blender -b -P blender/make_scythe.py` ואז `python tools/build_assets.py` |
| תצוגה מקדימה של כל התנוחות | `blender -b -P blender/preview_entity.py` + `python tools/sheet.py preview/frames preview/sheet.png 6` |
| לקרוא קוד של מיינקראפט/Fabric (אחרי `./gradlew genSources`) | `python tools/src.py net.minecraft.world.entity.Mob` · `-f שם` לחיפוש · `-g regex מחלקה` |

## 7. מפת קבצים

```
src/main/java/com/entity303/
  Entity303Mod.java                 כניסה, רישום
  registry/ModEntities, ModItems    רישום הישות והביצה
  entity/Entity303.java             הישות: חיים/שריון, שלבים, בוס בר, הסנכרון לקליינט
  entity/Entity303AttackGoal.java   כל ההתקפות + ההתחשבות בכמות השחקנים + Reaper's Descent
  anim/Entity303Animations.java     נוצר אוטומטית! (משכי אנימציה, טיקים של אירועים, מפתחות)
src/client/java/com/entity303/client/
  Entity303Client.java              רישום רנדרר ומודל
  render/Entity303Model.java        הפוזה (מפתחות אנימציה + הליכה + ראש + סיבוב המגל)
  render/Entity303Renderer.java     גודל (SCALE 1.5), שכבות זוהרות
  render/Entity303EyesLayer.java    עיניים זוהרות (ושלב 2: פנים זוהות באדום כהה)
  render/Entity303Geometry.java     נוצר אוטומטית! (קוביות המודל)
src/main/resources/               fabric.mod.json, lang, items/, models/, loot_table, טקסטורות (חלקן נוצרות)
skin/entity303_skin.png           הסקין האמיתי (64x64, פריסה קלאסית). שינוי → python tools/build_assets.py
tools/animations.py               *** מקור האמת לאנימציות (מפתחות בדרגות + אירועים) ***
tools/animations_fixed.py         נוצר אוטומטית מ‑animations.py ע"י autofix (לא לערוך)
tools/gen_assets.py               מייצר טקסטורות, Entity303Geometry.java, Entity303Animations.java
blender/make_scythe.py            המגל ב‑Blender → scythe_boxes.json (ואז gen_assets הופך אותו לקוביות במודל)
last_working_jar/                 ה‑jar האחרון שנבדק במשחק
reference/                        תמונות ייחוס
```

## 8. מלכודות ידועות

* **אל תערוך ידנית** `Entity303Geometry.java`, `Entity303Animations.java`, `animations_fixed.py`, הטקסטורות —
  הם נוצרים מחדש ב‑`python tools/build_assets.py`. לשנות תמיד את המקור (`animations.py`, `gen_assets.py`, הסקין, ה‑Blender).
* שינוי ב‑`tools/animations.py` (טיקים של אירועים/משכים) → להריץ `build_assets.py` **וגם** לוודא שהלוגיקה
  בצד השרת (`Entity303AttackGoal`) משתמשת באותם קבועים (`Entity303Animations.<ATTACK>_<EVENT>`) — כך ההתקפה והאנימציה מסונכרנות.
* שמות ב‑1.21.11 עם מיפוי Mojang: `Identifier` (לא `ResourceLocation`), רנדרר מבוסס `submit`/`RenderState`,
  `hurtServer(ServerLevel, DamageSource, float)`, `snapTo` (לא `moveTo`), `ValueInput/ValueOutput` לשמירה.
  כשלא בטוחים — `python tools/src.py <מחלקה>` אחרי `./gradlew genSources`.
* `EntityRendererRegistry` של Fabric מסומן deprecated (עובד, רק אזהרה).
* ישות שנוצרה לפני שינוי ב‑max health שומרת את הערך הישן — להזמין בוס חדש אחרי כל החלפת jar.
* ה‑jar חייב Fabric API באותו פרופיל (בשגיאת הגרסה של ה‑Loader זה לא נראה בהתחלה).
* ב‑`tools/jp.sh` וב‑`tools/src.py` יש נתיב ברירת מחדל `C:/rb` — לשנות ל‑`ENTITY303_ROOT`/הנתיב שלך.
* מעטפת Git Bash על ווינדוס "מתקנת" לפעמים `\\` בפקודות; כדאי להשתמש ב‑PowerShell/עורך לקבצים עם לוכסנים הפוכים.

## 9. חוזק לפי מספר שחקנים (אוקטובר 2026)

* `Entity303.CROWD_DAMAGE_PER_PLAYER` / `CROWD_TOUGHNESS_PER_PLAYER` (10% כל אחד) / `CROWD_CAP` (8): כל שחקן survival נוסף ברדיוס 40 בלוקים
  מוסיף 10% לנזק של ההתקפות (`damageMultiplier()`) ומוריד 10% מהנזק שהוא סופג (`hurtServer`). שחקן יחיד: בלי בונוס. חל בכל השלבים.
* שלב 3 הוחלש מעט: `FINAL_DAMAGE_BONUS` 2.0 → 1.5 (נזק בסיס שלב 3 מול שחקן יחיד: ×4.5 במקום ×6; מול 5 שחקנים ≈ ×6.3).
* ה‑Reaper's Descent לא מושפע מהמכפילים (50% מהחיים המקסימליים, 75% בשלב 3).

## 10. שינויים אחרי הבדיקה (Oktober 2026, v1.2.2)

* **לא בלתי מנוצח:** העמידות מול קהל מוגבלת ל‑24% (`CROWD_TOUGHNESS_PER_PLAYER` 6%, `CROWD_TOUGHNESS_MAX`), וההחלמה מגניבת חיים / Soul Drain מוגבלת
  ל‑`HEAL_PER_TICK` (0.4 = 8 חיים בשנייה בממוצע, עד `HEAL_BUDGET_MAX` 30 בבת אחת) דרך `Entity303.stealHealth`.
* **קורי עכביש** (וגם שיחי פירות יער ושלג אבקתי) לא מאטים אותו: `makeStuckInBlock` ריק.
* **Reaper's Descent:** רק מי שבתוך המעגל האדום (`IMPACT_RADIUS` 8) נפגע. מי שכבר קרוב (עד `PULL_STOP` = 11) לא נמשך ולא מוצמד לרצפה, ויכול לרוץ החוצה;
  הרחוקים נמשכים רק עד קצה השוליים (11), לא פנימה. הטבעת האדומה על הרצפה מסמנת בדיוק את אזור הפגיעה.
* **החלפת מטרות (נשאר כמו שהיה):** כל 5-10 שניות הוא בוחר שחקן אקראי (`maybeRetarget`), מיד אם המטרה רחוקה מ‑24 בלוקים ואחר קרוב, או רחוקה מ‑`TARGET_REACH` (40).
  `Shadow Step` קופץ לשחקן אקראי ו‑`Phantom Dance` מחליף מטרה בכל קפיצה. (ניסיתי מטרה דביקה בהתחלה, ובוטל לבקשתך.)

* **עמידת המנוחה (v1.2.3):** המגל מסתובב לצד הגוף (זרוע ימין פרושה לצד, `IDLE` ב‑`tools/animations.py`: `right_arm=[-10, 0, 95]`) ולא מלפנים. נבדק ב‑`collide.py` בכל זווית של הסיבוב. `drain` ו‑`guard` עדיין מחזיקים את הגלגל מלפנים בכוונה.
