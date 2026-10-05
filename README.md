# Easy Place Light Fix

Клиентский Fabric-мод, который чинит установку блоков **`minecraft:light`** через **Litematica Easy Place**.

| | |
|---|---|
| Автор | **not_oshi** |
| Версия мода | **1.3.1** |
| Minecraft | 26.2 (Java 25) |
| Fabric Loader | 0.19.5 |
| Litematica | 0.28.8 (проверено на этой версии) |
| Мод | [Litematica Printer](https://github.com/sakura-ryoko/litematica-printer) **не требуется** |
| Сторона | **только клиент**, на сервере ничего ставить не нужно |

> **Про Printer:** мод не требует Litematica Printer и не просит включить в нём никаких настроек.
> В Printer такой настройки и не существует — там нет переключателя «разрешить ставить свет».
> Принцип Printer (подмена разворота для сервера) в этом моде **переписан с нуля**, потому что Printer
> не даёт использовать его внутренности извне. Если в логе вы видите слово `Printer` — это только
> описание техники, а не просьба что-то установить.

> **Про EasyPlaceFix:** мод с ним **совместим**, в том числе когда клик делает EasyPlaceFix, а не
> Litematica. До 1.3.0 было наоборот: версии до 1.3.0 цеплялись только за переписанный путь, а
> EasyPlaceFix работает только на старом — см. «Два пути Easy Place».

> **Про 1.3.1:** функционально она равна 1.3.0 — свет ставился и там. Исправлены **все три дефекта
> диагностики** 1.3.0, из-за которых лог показывал `placed=0` при реальных постановках и молчащие
> снимки. Подробно — «Что было сломано в 1.3.0». Если у вас стоит 1.3.0 и блоки ставятся,
> обновление нужно только ради читаемости лога.

---

## Что было не так

### 1. Свет невидим для трассировки Litematica

Блок света в ванилле «материален» только когда игрок **держит light block в руке**:

```java
// net/minecraft/world/level/block/LightBlock (Minecraft 26.2)
protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext ctx)
{
    return ctx.isHoldingItem(Items.LIGHT) ? Shapes.block() : Shapes.empty();
}
```

Litematica ищет цель для Easy Place лучом по **миру схемы**, и в
`fi.dy.masa.litematica.util.RayTraceUtils#traceFirstStep` / `#traceLoopSteps` стоит жёсткий гейт:

```java
VoxelShape blockShape = blockState.getShape(world, data.blockPos, CollisionContext.of(player));
boolean blockCollidable = !blockShape.isEmpty();          // ← у light block = false
```

Один `@Redirect` на `BlockState#getShape` в этих двух методах даёт свету полный куб — ровно то
поведение, которое даёт ванилла, когда блок света уже в руке. Он покрывает сразу:

* обе ветки выбора цели — `EASY_PLACE_FIRST` и `getFurthestSchematicWorldTraceBeforeVanilla`;
* оба режима Litematica — старый `WorldUtils#doEasyPlaceAction` (активен, пока
  `easyPlacePostRewrite = false`) и новый `EasyPlaceUtils#handleEasyPlace`;
* трассировку блока-инфо, верификатор и pick-block по схеме.

### 2. Настоящая причина: кликнуть по воздуху нельзя

Даже когда цель найдена правильно (лог это показывал: `easy place target : [5469, 79, 4673]`,
`canPlaceBlock : true`, `click position : <null>`), Easy Place всё равно возвращал `FAIL`.

Причина — режим **`easyPlaceClickAdjacent`**, который у Litematica включён по умолчанию.
Кликнуть по воздуху ванильный протокол не умеет, поэтому `EasyPlaceUtils#getClickPosition`
перекладывает выбор на `getAdjacentClickPosition`, а тот ищет, **по какому существующему блоку
ударить**:

```java
// fi.dy.masa.litematica.util.EasyPlaceUtils#getAdjacentClickPosition, Litematica 0.28.8
HitResult hit = RayTraceUtils.getRayTraceFromEntity(level, cameraEntity, Fluid.NONE, false, reach);
if (hit.getType() == BLOCK) { /* точное попадание */ }

for (Direction dir : Direction.values()) {                 // запасной путь, offset 139
    BlockPos neighbour = targetPos.relative(dir);
    if (!PlacementUtils.isReplaceable(level, neighbour, false))
        return new BlockHitResult(facePoint, dir.getOpposite(), neighbour, false);
}
return null;                                                 // offset 217–218
```

Первый шаг — рейтрейс от камеры, который упирается в ту же пустую форму light block и пролетает
сквозь него (`vanilla crosshair : <no block hit>`). Запасной путь ищет **соседа, который не
замещается**, то есть твёрдый блок. Свет в воздухе (y = 78–80, схема в воздухе) имеет шесть
соседей-воздуха → цикл исчерпывается → `return null` → в `handleEasyPlace` срабатывает проверка
`clickPos == null || hand == null` (**offset 276, ordinal 5**) → `FAIL`.

То есть **на переписанном пути свет в воздухе поставить было невозможно в принципе**, независимо от
того, держит ли игрок предмет в руке. Цель была верной, предмет был в руке, `canPlaceBlock` = true —
блоку просто некуда было «кликнуть».

С `easyPlaceClickAdjacent = false` этой проблемы нет вообще: `getClickPosition` тогда возвращает
исходный `hitResult`, то есть клик прямо по клетке цели. Мод с 1.3.0 работает в обоих режимах,
потому что перехватывает именно `getClickPosition` (в разделе ниже — почему это важно).

> **Честное уточнение.** Пункт 1 выше показывает, что ванилла сама отдаёт light block полный куб,
> когда игрок **держит light block в руке**. То есть вполне реальная комбинация — Easy Place,
> предмет в руке, свет на один блок выше опоры — ванильным протоколом ставится и без всякого мода.
> Настоящая ценность мода не в этом, а в трёх вещах, которые ванилла не покрывает: подстановка клика
> в режиме `easyPlaceClickAdjacent`, случай с пустой формой (`EMPTY` — предмет в другой руке или
> форма не сработала) и разворот под сервер. Если у вас срабатывает первый случай — мод вам не нужен,
> и это нормально.

---

## Два пути Easy Place (важно, если стоит EasyPlaceFix)

У Litematica **две полностью независимые реализации** Easy Place, и переключатель
`easyPlacePostRewrite` выбирает между ними. Из `WorldUtils#handleEasyPlace`, Litematica 0.28.8:

```
 9: EASY_PLACE_POST_REWRITE.getBooleanValue()
15: ifne 104        // ON  -> return false, doEasyPlaceAction вообще не вызывается
27: invokestatic doEasyPlaceAction(mc)
```

| | `easyPlacePostRewrite = false` (старый путь) | `easyPlacePostRewrite = true` (переписанный) |
|---|---|---|
| кто ставит блок | `WorldUtils#doEasyPlaceAction` | `EasyPlaceUtils#handleEasyPlace` |
| как выбирается клик | сразу по клетке цели, поиска опоры нет | `getClickPosition`: либо сосед, либо сама клетка |
| свет в воздухе | **работает из коробки**, мод не нужен | без мода `FAIL`, нужен мод |
| **EasyPlaceFix** | **работает** | **полностью инертен** |
| мод 1.3.1 | нужен только разворот (клик и так правильный) | работает целиком |
| мод 1.2.1 и старше | **полностью инертен** | работает целиком |

> **Почему EasyPlaceFix «перестаёт работать».** Это не мод. У EasyPlaceFix
> ([ServChan/EasyPlaceFix](https://github.com/ServChan/EasyPlaceFix)) **единственная** точка входа —
> инъекция в `WorldUtils#doEasyPlaceAction`. Включили `easyPlacePostRewrite` — метод перестал
> вызываться, и весь мод EasyPlaceFix (развороты под ориентацию, темп тиков, настройка нотных
> блоков, взаимодействие с контейнерами) молча выключился. Обратно: если EasyPlaceFix нужен —
> держите `easyPlacePostRewrite = false`, и мод 1.3.0 всё равно добавит свою часть.

Именно эта таблица объясняет и второе сообщение обратной связи — «мод работает даже когда наш мод
выключен». На старом пути свет в воздухе ставится нативно: `doEasyPlaceAction` строит клик сразу по
клетке цели (`new BlockHitResult(hitVec, facing, pos, false)`, offset 530) и не ищет опору, поэтому
мод там и не нужен.

**Что делать:**

* хотите EasyPlaceFix → `easyPlacePostRewrite = false`. Мод 1.3.0 при этом всё равно добавит
  разворот для света (в том числе когда клик делает EasyPlaceFix — хук висит на ванильном
  `useItemOn`, а не внутри Litematica);
* не нужен EasyPlaceFix → `easyPlacePostRewrite = true`, мод работает целиком;
* настройка `easyPlaceClickAdjacent` **не имеет значения** с 1.3.0 — мод цепляется за
  `getClickPosition`, через который проходят обе ветки.

При запуске мод сам печатает, какой путь активен и стоит ли EasyPlaceFix:

```
[INIT] which path is live  : easyPlacePostRewrite = ON, so EasyPlaceUtils#handleEasyPlace does ...
[INIT] easyplacefix         : installed. It hooks WorldUtils#doEasyPlaceAction, which only runs when ...
```

---

## Что было сломано в 1.0–1.2 (и почему мод «работал не всегда»)

Два бага, оба из-за неверного выбора точки хука. Оба исправлены в 1.3.0.

### 1. Зависимость от `easyPlaceClickAdjacent`

Хук стоял в `HEAD` у `EasyPlaceUtils#getAdjacentClickPosition(BlockPos)`. Но этот метод
`getClickPosition` вызывает **только на одной ветке**:

```java
// EasyPlaceUtils#getClickPosition, Litematica 0.28.8
 0-18: if (state.getBlock() instanceof SlabBlock)
           return getClickPositionForSlab(hitResult, state, schematicState);   // areturn #0
19-23: BlockPos targetPos = hitResult.getBlockPos();
25-31: boolean clickAdjacent = EASY_PLACE_CLICK_ADJACENT.getBooleanValue();
33-43: if (clickAdjacent)
           return getAdjacentClickPosition(targetPos);   // ← наш хук был только здесь
46-47: return hitResult;                                 // ← а здесь мода не было
```

С опцией **выключенной** мод становился полным no-op — а его собственная диагностика при этом
обвиняла `directClickForLightBlocks`. Ошибка выглядела как «мод сломан», хотя настройка была
не при чём.

**Исправление:** хук переехал на `getClickPosition`, `@At(value = "RETURN", ordinal = 1)` —
это как раз та единственная точка возврата, через которую проходят обе ветки. Цена — поиск соседа
всё-таки выполняется (один рейтрейс на клик по свету), но его результат просто заменяется.

### 2. Разворот был не виден EasyPlaceFix

Хук разворота был `@Redirect` на вызов `MultiPlayerGameMode#useItemOn` **внутри**
`EasyPlaceUtils#handleEasyPlace`. Но:

* на старом пути `handleEasyPlace` не вызывается вообще;
* EasyPlaceFix **заменяет** `WorldUtils#doEasyPlaceAction`, а не дополняет его;
* свои клики EasyPlaceFix делает из своей очереди `TickThread`, то есть из другого места.

Три слепые зоны, и в сочетании с первым багом они и давали «работает только когда включено то самое».

**Исправление:** хук переехал в `HEAD` ванильного `MultiPlayerGameMode#useItemOn` — через этот метод
проходят все три пути. Возвращаемое значение не трогается, отправляется только лишний пакет.

Ещё один нюанс, который пришлось учесть: для light block `useItemOn` реально входит **дважды** —
сначала ванильный клик игрока, потом вложенный клик Easy Place. Оба несут одну и ту же клетку, а
разворот нужен один, поэтому в `PrinterDelivery` дедупликация по `(позиция, игровое время)`, а не по
глубине вложенности: у `HEAD`-инъекции нет момента «после вызова».

---

## Что было сломано в 1.2.0 (и почему был «инстакраш»)

1.2.0 работала — блоки ставились — но при клике по свету, для которого срабатывал возврат потерянной
цели, игра **падала**. Разбор стектрейса из `logs/latest.log`:

```
[Render thread/ERROR]: Unreported exception thrown!
org.spongepowered.asm.mixin.injection.callback.CancellationException:
    The call getTargetPosition is not cancellable.
	at CallbackInfo.cancel(CallbackInfo.java:101)
	at CallbackInfoReturnable.setReturnValue(CallbackInfoReturnable.java:106)
	at EasyPlaceUtils...notoshi$rescueLightTarget(EasyPlaceUtils.java:1562)
	at EasyPlaceUtils.getTargetPosition(EasyPlaceUtils.java:230)
	at EasyPlaceUtils.handleEasyPlace(EasyPlaceUtils.java:460)
	at EasyPlaceUtils.handleEasyPlaceWithMessage(EasyPlaceUtils.java:151)
	at MultiPlayerGameMode...onInteractBlock(MultiPlayerGameMode.java:1145)
	at Minecraft.startUseItem(Minecraft.java:1818)
	at Minecraft.tick(Minecraft.java:1886)
```

`@Inject(method = "getTargetPosition(...)", at = @At("RETURN"))` был объявлен **без
`cancellable = true`**, а хук вызывал `cir.setReturnValue(...)`. `setReturnValue` внутри вызывает
`CallbackInfo#cancel`, а `cancel` бросает исключение, если инъекция не объявлена отменяемой:

```
CallbackInfoReturnable#setReturnValue → CallbackInfo#cancel → CancellationException
```

Исключение **ничем не перехватывается**: `handleEasyPlace` вызывается прямо из `Minecraft#tick`,
и оно уходит в обработчик необработанных исключений рендер-потока → `emergencySaveAndCrash`.

Почему падало «иногда» и «не на ту грань»: исключение бросалось **ровно тогда и только тогда**, когда
возврат цели что-то нашёл (`setReturnValue` вызывается только при `rescued != null`). А блок при этом
уже оказывался поставленным, потому что успешный клик прошёл на предыдущем тике того же зажатия
кнопки. Отсюда и «блок после захода всё равно стоит».

**Исправление в 1.2.1:**

1. `cancellable = true` на этом `@Inject` — это и есть всё исправление;
2. **все** три хука в `MixinEasyPlaceUtilsDelivery` обёрнуты в `try/catch (Throwable)` с откатом на
   стоковое поведение Litematica. Эти хуки работают посреди клика Litematica, на рендер-потоке, без
   единого барьера исключений — любая ошибка в них убивает игру. Диагностика, не стоивщая краша игры,
   не является диагностикой;
3. появился тег `[HOOK-FAIL]`, который печатается, когда хук сработал и ошибку поймал.

---

## Что было сломано в 1.3.0 (три ошибки, все — в диагностике)

1.3.0 починила обе функциональные проблемы (работа вместе с EasyPlaceFix и независимость от
`easyPlaceClickAdjacent`), но её диагностика отдавала неверные числа. Свет при этом ставился.
Итог сессии тестера:

```
[SUMMARY] passed=47 placed=0 blocked=180 directClicks=19
```

Три независимых дефекта:

### 1. `@Redirect` по статической цели не работает — и роняет весь класс миксина

`placementRestrictionInEffect()`, `canPlaceBlock()` и `doEasyPlaceAction()` — все `static`. Все три
обработчика объявляли возвращаемое значение **последним параметром**, а Mixin для статической цели
передаёт его как тип возврата обработчика, а не как аргумент. Обе ошибки подробно разобраны ниже.

Цена: не три хука, а **всё содержимое двух mixin-классов**. `MixinEasyPlaceUtilsDebug` и
`MixinWorldUtilsDebug` помечены `"required": false`, поэтому игра не упала — в `logs/latest.log`
оказалось две строки `Mixin apply for mod easyplace_lightfix failed`, а в `easyplace_lightfix.log`
молчали счётчик `restrictionTrue` (всегда 0) и все снимки legacy-пути.

Симптом, по которому это видно снаружи: **`restrictionTrue=0` при `blocked=180`** и
`<restriction check not reached>` в каждом снимке, хотя клики отклонялись.

### 2. `placed` был инвертирован

Подробно разобрано в таблице ordinal'ов ниже. `placed` рос на том возврате, который означает «ничего
не поставлено», и потому был неспособен стать ненулевым. `placed=0` при 19 успешных постановках
читается как полный провал, хотя блоки ставились.

### 3. `easyPlacePostRewrite` читался только при старте

Тестер переключает настройки посреди сессии, а баннер печатался один раз при запуске и завечно врал:
`easyPlacePostRewrite = OFF` в баннере и `[CLICK] post-rewrite` через минуту. Два пути делили один
набор полей, так что снимок мог описать `areturn site` метода, который не выполнялся.

**Исправление в 1.3.1:**

1. все четыре `@Redirect` удалены, значения наблюдаются пиннятыми `@Inject`;
2. вердикт читается напрямую с `useItemOn`; добавлен счётчик `refused`; `placed` переведён на
   настоящее условие постановки;
3. добавлен хук на `HEAD` у `doEasyPlaceAction`, сбрасывающий состояние клика; снимки `[CLICK]`
   показывают живой путь и реальный ответ ваниллы.

---

## Как работает мод

**Litematica решает, Printer доставляет.** Все решения о размещении остаются у Litematica —
`getTargetPosition`, `canPlaceBlock`, `applyPlacementProtocolAll`, выбор предмета, проверка
reach. Мод не придумывает ни одной из этих вещей. Он чинит только доставку, и только для
`minecraft:light`.

Хуков три, все — в `required: true`-конфиге, потому что это и есть поведение мода.

### 1. Прямой клик по целевой клетке — хук, который чинит баг

`@Inject` + `cancellable` на `EasyPlaceUtils#getClickPosition(...)`,
`@At(value = "RETURN", ordinal = 1)`.

Когда цель — `minecraft:light` и клетка ещё свободна в клиентском мире, мод отвечает
собственным `BlockHitResult` по грани этой клетки и подменяет им ответ Litematica.

`ordinal = 1` выбран не на глаз: у метода ровно **две** точки возврата, и вторая
(байткод-offset 47) — та самая, через которую проходят **обе** ветки
`easyPlaceClickAdjacent`. Первая (offset 18) — это ранний выход для плит, свет туда не попадает.

Почему сервер это принимает: `BlockItem` кладёт блок туда, куда разрешает
`BlockPlaceContext#canPlace()`, а это

```java
// net.minecraft.world.item.context.BlockPlaceContext#canPlace(), Minecraft 26.2
return this.replaceClicked || level.getBlockState(getClickedPos()).canBeReplaced(context);
```

Воздух `canBeReplaced` = `true`, поэтому клик по пустой клетке заполняет её. Проверено на
байткоде 26.2. Сервер проверяет дальность по **позиции блока**
(`isWithinBlockInteractionRange(blockPos, 1.0000001)`), а не по вектору клика, — а позиция уже
прошла проверку reach'а самого Easy Place.

Точку клика берём как центр грани, обращённой к глазам игрока, — ближайшую точку клетки, по той же
логике, что и `getAdjacentClickPosition` для своих попаданий.

Условия, при которых мод **не** вмешивается:

| условие | почему |
|---|---|
| `directClickForLightBlocks = false` | пользователь попросил стоковое поведение |
| цель — не `minecraft:light` в схеме | ничего кроме света не трогаем |
| клетка в клиентском мире уже занята | штатный клик по соседу здесь корректнее |

### 2. Возврат потерянной цели

`EasyPlaceUtils#getTargetPosition` возвращает `null`, когда ближайшее попадание луча — ванильный
блок, а не блок схемы:

```java
if (trace != null && trace.getHitType() == SCHEMATIC_BLOCK)
    return trace.getBlockHitResult();
return null;                     // ванильный блок выиграл луч
```

Из-за этого `handleEasyPlace` возвращает `FAIL` на **ordinal 1** (offset 123), **не успев построить
клик**. Свет — полный куб, и он проигрывает это сравнение любой обычной геометрии перед ним.

Когда возвращается `null`, мод перезапускает **собственный** `RayTraceUtils#traceToSchematicWorld` —
тот же метод, которым пользуется сам `getGenericTrace` — и подставляет результат, если это правда
`minecraft:light`. Дистанция та же (`min(reach, 6.0)`), так что мод не может увидеть дальше, чем
видит Easy Place.

> Именно этот хук и ронял игру в 1.2.0 — см. раздел выше.

### 3. Разворот для сервера

`@Inject` в `HEAD` ванильного `MultiPlayerGameMode#useItemOn`. Возвращаемое значение не трогается
— отправляется только лишний пакет.

Перед кликом по light block отправляется один
`ServerboundMovePlayerPacket.Rot` с yaw/pitch целевой грани — ровно то, что делает Litematica
Printer в `PrepareAction`:

```
горизонтальная грань -> yaw = face.toYRot(), pitch = 0
UP                    -> pitch = -90  (yaw не трогаем)
DOWN                  -> pitch = +90  (yaw не трогаем)
```

`setYRot`/`setXRot` у локального игрока **не вызываются**, поэтому камера не дёргается. Разворот уходит
раньше клика по тому же соединению, так что к моменту, когда сервер обработает размещение, он уже
действует.

**Почему хук висит на `useItemOn`, а не внутри Litematica.** Через этот ванильный метод проходят все
три варианта клика: переписанный путь Litematica, старый путь и клики из очереди EasyPlaceFix.
Подробности — в разделе «Что было сломано в 1.0–1.2», пункт 2; именно из-за этого хук и уехал сюда.

**Условия отправки.** Пакет уходит, только если одновременно:

| условие | зачем |
|---|---|
| `rotateForLightBlocks = true` | пользователь не просил стоковое поведение |
| в схеме по этой клетке `minecraft:light` | ни один другой блок не трогаем |
| клетка в клиентском мире `canBeReplaced()` | иначе это клик по уже поставленному блоку, а не установка |

**Дедупликация.** Для light block `useItemOn` входит **дважды**: сначала ванильный клик игрока, потом
вложенный клик Easy Place изнутри того же вызова. Оба несут одну и ту же клетку, а разворот нужен
один. У `HEAD`-инъекции нет момента «после вызова», поэтому счётчик вложенности не работает — вместо
него в `PrinterDelivery` запоминается `(позиция, игровое время)` последнего разворота. Если
EasyPlaceFix действительно ставит два разных блока света за один тик, разворот будет для каждого.

## Что НЕ меняется

* **Ванилла не трогается.** `LightBlock#getShape` остаётся как есть — перекрестие по-прежнему не
  цепляется за light block, и стандартный хит-тест его не видит.
* **Форма столкновений не меняется** — свет по-прежнему можно проходить, он не перекрывает обзор и
  не блокирует свет.
* **Только для `minecraft:light`.** Любой другой блок идёт ровно по стоковому пути Litematica, лишних
  пакетов не отправляется вообще.
* **Настройки Easy Place трогать не нужно.** `easyPlaceClickAdjacent` не имеет значения с 1.3.0 —
  мод компенсирует ровно тот случай, когда клик выбрать нечем, в обоих режимах. Ванильный
  `LightBlock#getShape` не патчится.
* **Сам мод ничего не переключает.** `easyPlacePostRewrite` — это выбор между двумя *разными*
  реализациями Easy Place в самой Litematica, и мод уважает тот выбор, который сделан. Что именно
  делает мод в каждом из двух режимов — в таблице выше.

## ⚠️ Видимость для сервера

> **Прежнее обещание README — «на сервере палится не больше, чем обычное размещение блока» — больше
> неверно, и я его отзываю.** Разворот виден серверу.

Сервер видит направление взгляда, которое клиент никогда не рендерил. Это строго заметнее обычного
размещения блока, и любой античит, отслеживающий look direction, это увидит.

Это свойство самого метода Printer, а не нашей реализации. Если для вас невидимость важнее
успешного размещения — выключите `rotateForLightBlocks` в `config/easyplace_lightfix.properties`.
Работать будет: пункт 1 (прямой клик по клетке) от него не зависит.

---

## Установка

1. Нужен **Fabric Loader** и **Litematica 0.28.8+** с malilib.
2. Киньте `easyplace_lightfix-26.2-1.3.1.jar` в `.minecraft/mods/`.
3. **Удалите из `mods/` все версии кроме 1.3.1** (1.0.1, 1.0.2, 1.1.0, 1.2.0, 1.2.1, 1.3.0). Fabric Loader
   грузит все jar'ы, и две копии easyplace_lightfix в папке приводят к дублям сообщений в логе.
4. **Если копия мода оказалась в `resourcepacks/` — перенесите её в `mods/`.** Minecraft попытается
   прочитать jar как ресурспак и напишет `Error reading pack metadata` с
   `JsonParseException` при каждой перезагрузке. На работу мода это не влияет, но засоряет лог
   примерно двадцатью лишними трассировками стека за запуск.
5. **На сервер мод не ставится** — он клиентский, и серверу ничего от него не требуется.

## Проверка, что фикс сработал

Баннер при запуске перечисляет, что искать в логе:

```
[EasyPlaceLightFix/INIT] placement works when : [CLICKPOS] and [ROT] appear for a minecraft:light target.
[EasyPlaceLightFix/INIT] no [CLICKPOS] ever   : directClickForLightBlocks is off, ...
[EasyPlaceLightFix/INIT] no [ROT] after it    : rotateForLightBlocks is off, ...
[EasyPlaceLightFix/INIT] easyPlaceClickAdjacent: not consulted by this mod since 1.3.0. ...
[EasyPlaceLightFix/INIT] which path is live  : easyPlacePostRewrite = ON, so EasyPlaceUtils#handleEasyPlace ...
[EasyPlaceLightFix/INIT] easyplacefix         : installed. It hooks WorldUtils#doEasyPlaceAction, which only ...
[EasyPlaceLightFix/INIT] Nothing above is a failure report. ...
```

> В версиях до 1.2.1 баннер содержал строку
> `*** in [BLOCKED] : handleEasyPlace was overwritten or extended by another mod. Disable easyplacefix ...`
> Это была **неверная диагностика**: настоящей причиной был собственный хук мода, осевший не на ту
> инструкцию, а не чужой мод. Строка удалена в 1.2.1.
>
> В 1.3.0 баннер, наоборот, **называет** EasyPlaceFix — но только факт его установки и объяснение,
> что он цепляется за `doEasyPlaceAction`, то есть работает только при `easyPlacePostRewrite = false`.
> Это информация, а не обвинение: мод с ним совместим.

Если в баннере `litematica : <not loaded>` — Litematica не загружена, мод ничего не делает.

Дальше нужен **первый** `[FIX-ACTIVE]` — он появляется в тот момент, когда трассировка схемы впервые
спросила форму у блока света:

```
[FIX-ACTIVE] redirect fired for minecraft:light at [x, y, z] -> the mixin IS applied and
             Litematica's schematic ray trace can now see it
```

Если `[FIX-ACTIVE]` так и не появился, а Litematica при этом ругается на mixin — значит версия
Litematica изменилась и целевые методы переехали. Смотрите раздел «Обновление» ниже.

Полный список тегов:

| тег | когда |
|---|---|
| `[CLICKPOS]` | **главная строка.** Прямой клик по клетке света — основной фикс |
| `[ROT]` | Отправлен `ServerboundMovePlayerPacket.Rot` перед кликом по свету |
| `[RESCUED]` | Easy Place потерял цель, мод её вернул |
| `[TRACE]` | Срабатывание подмены формы (с троттлингом; счётчик растёт постоянно, это нормально) |
| `[CLICK]` | Клик Easy Place дошёл до конца. Показывает путь, **настоящий** вердикт ваниллы и счётчики |
| `[BLOCKED]` | Подробный снимок причины отказа |
| `[ARMED]` | Состояние ворот Litematica (см. ниже) — информационно |
| `[HOOK-FAIL]` | Хук мода бросил исключение, мод его поймал и откатился на стоковое поведение |
| `[SUMMARY]` | Итоговые счётчики и вердикт при выходе из игры |

### Что означают `[CLICKPOS]` и `[ROT]`

```
[CLICKPOS] direct light click is live -> clicking the target cell itself at [5469, 79, 4673]
           face=west (getClickPosition's answer was replaced, so this works with easyPlaceClickAdjacent
           either on or off)  [directClicks=1]
[ROT] sent ServerboundMovePlayerPacket.Rot yaw=90.0 pitch=0.0 face=west (camera unchanged)
```

`[CLICKPOS]` — **это и есть фикс**. Он означает, что мод увидел цель-свет, клетка свободна, и подменил
ответ Litematica кликом прямо по цели. Без этой строки свет в воздухе на переписанном пути поставить
нельзя в принципе.

`[ROT]` — доставка пошла по пути Printer, значит до клика дело дошло.

| симптом в логе | что значит |
|---|---|
| `[CLICKPOS]` есть, `[ROT]` нет | цель найдена, но до `useItemOn` код не дошёл — смотрите `areturn site` |
| `[CLICKPOS]` есть, `[ROT]` есть, блока нет | сервер отверг. Включите `rotateForLightBlocks`, если выключали |
| `[ROT]` есть, `[CLICKPOS]` нет | **старый путь** (`easyPlacePostRewrite = false`) — там клик и так правильный, мод его не трогает. Норма |
| `[CLICKPOS]` нет вообще | на переписанном пути фикс не применился: проверьте `directClickForLightBlocks` и версию Litematica |
| `[HOOK-FAIL]` | хук мода упал и был пойман; клик ушёл без него. Пришлите лог |
| `unattributedFails` > 0 в `[SUMMARY]` | версия Litematica не совпадает с разобранной. Пришлите лог |

### ⚠️ `[BLOCKED]` после удачного `[ROT]` — это нормально

Самая частая путаница в логе. Litematica входит в Easy Place **дважды** на одно нажатие (из своего
хука на `useItemOn` и из своей горячей клавиши), и после того как блок уже поставлен, следующая
попытка того же зажатия кнопки честно получает `FAIL`:

```
[ROT] sent ... -> блок поставлен
[BLOCKED] rejected because : ordinal 0 / offset 79 - FAIL: no schematic target on the ray AND the
                             placement restriction is active. This is the NORMAL, HARMLESS outcome ...
```

Клетка теперь занята, лучу нечего попасть, `placementRestrictionInEffect()` отвечает `true` — и Litematica
показывает штатное «Действие заблокировано». **Это не поломка мода и не ошибка установки.** Ориентируйтесь
на `[CLICKPOS]` + `[ROT]`, а не на отсутствие `[BLOCKED]`.

### Про строки `[ARMED]`

```
[ARMED] Litematica's own gate says Easy Place may act right now.
[ARMED] Litematica's own gate says Easy Place must not act right now. That gate is easyPlaceMode +
        not the REBUILD tool mode + the activation key held; ...
```

Это не диагноз, а чужое состояние. `shouldDoEasyPlaceActions()` в Litematica 0.28.8 — это ровно:

```java
Configs.Generic.EASY_PLACE_MODE.getBooleanValue()
    && Configs.Generic.EASY_PLACE_POST_REWRITE.getBooleanValue()
    && GameWrap.getClientPlayer() != null
    && DataManager.getToolMode() != ToolMode.REBUILD
    && Hotkeys.EASY_PLACE_ACTIVATION.getKeybind().isKeybindHeld();
```

Ни одно из этих условий мод не проверяет и не меняет, и строка печатается не чаще раза в секунду.
Если установка работает — просто игнорируйте её. Если блоки **не** ставятся вообще, первое, что стоит
проверить, — что горячая клавиша активации Easy Place в Litematica вообще нажата и что мод не стоит
в режиме инструмента REBUILD.

### Строка `areturn site` — самая важная в `[BLOCKED]`

`EasyPlaceUtils#handleEasyPlace()` в Litematica 0.28.8 содержит **девять** точек возврата, и шесть из
них могут вернуть `InteractionResult.FAIL`. Снаружи они неотличимы — все выглядят одинаково. Поэтому
мод пиннит **каждую** точку отдельным `@At(value = "RETURN", ordinal = n)` и печатает, какая сработала:

```
[BLOCKED] rejected because     : ordinal 5 / offset 276 - FAIL: getClickPosition() returned null ...
[BLOCKED] areturn site         : ordinal 5 / offset 276 - FAIL: getClickPosition() returned null ...
```

| ordinal | offset | условие |
|---|---|---|
| 0 | 79 | `FAIL` — активен placement restriction и схематический луч вообще ничего не нашёл. **Обычное дело сразу после того, как свет уже поставлен** |
| 1 | 123 | `FAIL` — активен placement restriction и `getTargetPosition()` вернул `null` (ближайшим по лучу оказался **ванильный** блок) |
| 2 | 127 | **всегда `PASS`**, никогда не `FAIL` |
| 3 | 185 | `FAIL` — блок в теге, который Litematica отказывается ставить в один клик |
| 4 | 231 | `FAIL` — `!canPlaceBlock()`, **или** нет известного предмета, **или** блок уже верный, **или** позицию только что пробовали |
| **5** | **276** | **`FAIL` — `clickPos == null` или предмет не в руке. Именно сюда попадал свет в воздухе** |
| 6 | 580 | `FAIL` — placement protocol запретил блок (касается только факела / знамени / таблички / черепа) |
| 7 | 955 | `SUCCESS` — **`useItemOn` ответил `PASS`, то есть НИЧЕГО НЕ ПОСТАВЛЕНО** |
| 8 | 959 | `PASS` — **`useItemOn` ответил что-то другое, то есть клик ушёл наружу.** Поставился ли блок — см. строку `vanilla said` |

> **Про ordinal'ы 7 и 8 — они означают противоположное тому, что написано в их именах.** Это была
> ошибка 1.0–1.3.0, исправленная в 1.3.1. Байткод в конце `handleEasyPlace`:
>
> ```
> 754: invokevirtual MultiPlayerGameMode.useItemOn:(...)Lnet/minecraft/world/InteractionResult;
> 757: astore 29
> 759: aload 29
> 761: getstatic InteractionResult.PASS
> 764: if_acmpne 956            // useItemOn != PASS  ->  к «голому» PASS-возврату
> ...
> 952: getstatic SUCCESS
> 955: areturn                  // ordinal 7
> 956: getstatic PASS
> 959: areturn                  // ordinal 8
> ```
>
> `BlockItem#place` при успехе возвращает `InteractionResult.SUCCESS` (его байткод-offset 268),
> а на каждой из пяти своих веток отказа — `FAIL`. `PASS` он не возвращает **никогда**. Значит
> `PASS` от `handleEasyPlace` = блок поставлен, а `SUCCESS` = клик ничего не сделал.
> Счётчик `placed` в 1.0–1.3.0 рос именно на ordinal 7, то есть в единственном случае, который
> ничего не ставит, и поэтому был структурно неспособен стать ненулевым.

> **Про ordinal 2 и «сдвиг на единицу».** В версиях до 1.2.0 ярлыки в таблице были сдвинуты, потому
> что `ordinal 2` ошибочно считали ещё одной `FAIL`-веткой. Настоящая причина отказа
> (`ordinal 5`, «нечего кликнуть») подписывалась как «placement protocol запретил блок» — прямо не туда.

> **Про `*** FAIL matched NO areturn hook`.** Такой строки в 1.2.1 больше нет, и это не потеря
> диагностики, а исправление ложной тревоги. Строка появлялась из-за **собственного** хука мода
> с голым `@At("RETURN")`, который в Mixin 0.8.7 попадал на ту же инструкцию, что и `ordinal 0`,
> а не на последнюю. Результат: `FAIL` без атрибуции плюс баннер, обвиняющий сторонний мод, которого
> в списке модов даже не было. Теперь все девять точек пиннятся явным `ordinal`, и `FAIL` не может
> прийти неатрибутированным. Счётчик `unattributedFails` в `[SUMMARY]` — это tripwire: он всегда 0,
> и если он не 0, значит версия Litematica не совпадает с разобранной.

Снимок `[BLOCKED]` дополнительно печатает `easy place target`, `placement restrict.`,
`canPlaceBlock`, `click position`, `direct light click`, `vanilla said` и `player sees message` —
это те самые значения, которые проверяет указанная ветка, поэтому снимок объясняет вердикт, а не
просто повторяет его.

> Строка `placement restrict.` с 1.3.1 показывает не возврат `placementRestrictionInEffect()`, а то,
> взведён ли **экранный** ворот Litematica (единственная точка наблюдения — возврат
> `handlePlacementRestriction`). В 1.3.0 строка не могла показать ничего осмысленного: хуки, которые
> её наполняли, были отброшены Mixin, и она печатала свой дефолт `<restriction check not reached>`
> на каждом клике, включая отклонённые именно этой проверкой.

### Как сделано так, чтобы диагностика не врала

Три бага диагностики 1.0–1.2 выросли из одного: **`@At("RETURN")` без `ordinal` в Mixin 0.8.7 попадает
на первую подходящую инструкцию, а не на последнюю**. Поэтому правило теперь жёсткое:

| где | как |
|---|---|
| `handleEasyPlace` | все 9 `areturn` пиннятся явным `ordinal` (0..8) |
| `getTargetPosition` | 4 точки: ordinal'ы 0, 1, 2 — диагностике, ordinal 3 — хуку поведения |
| `getClickPosition` | 2 точки: ordinal 0 — диагностике, ordinal 1 — хуку поведения |
| `canPlaceBlock` | 3 `ireturn`, все пинняты (0, 1, 2) |
| `handlePlacementRestriction` | 1 `ireturn`, пиннят — это единственное место, где видно, что воротфорс Litematica взведён |
| `handleEasyPlaceWithMessage` | все 3 `ireturn` пиннятся (0, 1, 2) |
| `WorldUtils#handleEasyPlace` | все 3 `ireturn` пиннятся (0, 1, 2) |
| `WorldUtils#doEasyPlaceAction` | 11 `areturn` **не** пиннятся поштучно — вместо этого вход хука на `HEAD`, см. ниже |

#### ⚠️ `@Redirect` по статической цели не может наблюдать возвращаемое значение

1.3.0 пытался закрыть `placementRestrictionInEffect()` (7 `ireturn`, 3 вызова) и `canPlaceBlock`
(3 `ireturn`, 1 вызов) не инъекциями, а `@Redirect` по местам вызова — рассуждение было: так покрываются
все вердикты **по построению**. Оно неверно, и вот почему.

Mixin собирает сигнатуру обработчика `@Redirect` из параметров цели и её **типа возврата**.
Возвращаемое значение он **не передаёт параметром** — оно и есть тип возврата обработчика. Обойти это
нельзя: чтобы узнать исходный вердикт, обработчик должен вызвать целевой метод, а это немедленно
перевходит в тот же `@Redirect` и уходит в бесконечную рекурсию. Обе попытки из лога тестера:

```
notoshi$debugRestrictionFirst(Z)Z
  Found 1 unexpected additional method arguments: (boolean)

notoshi$legacyResult(Minecraft, InteractionResult)
  Found unexpected argument type net.minecraft.world.InteractionResult at index 1,
  expected net.minecraft.client.Minecraft
  Expected signature: (Lnet/minecraft/client/Minecraft;
                       Lnet/minecraft/client/Minecraft;)Lnet/minecraft/world/InteractionResult;
```

Обе сигнатуры выглядят разумно — в этом и ловушка. И оба отказа отменили применение **всего класса**
миксина, то есть стоили не трёх хуков, а всей диагностики в этих двух файлах. Симптом — одна строка
`WARN` в `logs/latest.log`, потому что диагностические конфиги помечены `"required": false`.

Правильный способ — скучный: **по одной пиннятой инъекции на каждую точку возврата**. Где вердикт
не нужен, он не наблюдается вовсе:

* `placementRestrictionInEffect()` **не наблюдается**. Три её вызова — это `handleEasyPlace` на
  offset'ах 70 и 108, и `handlePlacementRestriction`. Первые два немедленно дают `FAIL`, уже пиннятые
  как ordinal'ы 0 и 1, так что вердикт `true` там спрятаться не может; третий запинен один раз.
  Счётчик `restrictionTrue`, который в 1.0–1.3.0 показывал 0 целую сессию (потому что класс не
  применился), удалён, а не починен: счётчик, который может быть только нулём, хуже отсутствия
  счётчика. `blocked` + `areturn site` покрывают ту же информацию.
* `placementRestrictionInEffect(Minecraft)` на legacy-пути (8 `ireturn`) — по той же причине.
  `doEasyPlaceAction` имеет 11 точек возврата, и пиннить их все ради пути, который не нуждается в
  подмене клика, — это обслуживание без диагностической отдачи. Вместо этого хук на `HEAD` помечает
  сам факт захода, а вердикт приходит из единственного `FAIL` у `handleEasyPlace` (ordinal 0, offset 90
  — именно там печатается `easy_place_fail`). Он не может пропустить отказ.
* `doEasyPlaceAction` на `HEAD` заодно **сбрасывает состояние клика**, как это делает
  `notoshi$attemptStart` на переписанном пути. В 1.3.0 этого не было, и два пути делили один набор
  полей: тестер переключал настройку прямо посреди сессии, баннер при запуске писал
  `easyPlacePostRewrite = OFF`, а через минуту в логе шли строки `[CLICK] post-rewrite`, а снимки
  описывали `areturn site` метода, который не выполнялся.

Единственный `@Redirect`, который в моде остаётся — в `MixinRayTraceUtils`: там цель
`BlockState#getShape` **экземплярная**, Mixin ставит получателя первым параметром, и возвращаемое
значение есть тип возврата обработчика. Неоднозначности нет.

И второе, не менее важное правило: **инъекция после отменяющей на той же инструкции не выполняется
никогда** (`CallbackInfo#cancel` бросает немедленно). Поэтому две точки —
`getTargetPosition` ordinal 3 и `getClickPosition` ordinal 1 — принадлежат хуку поведения, и он сам
записывает подменённое значение в диагностику. Диагностический хук на той же инструкции был бы
мёртвым кодом, который выглядит живым: в логе 1.2.1 рядом с `[RESCUED]` печаталось
`<target lookup not reached>` при заведомо выполненном поиске.

### `placed` и `refused` — единственные честные числа в логе

Ни один счётчик, построенный на возвращаемом `InteractionResult` Litematica, нельзя читать буквально
(см. ordinal'ы 7 и 8 выше). Поэтому с 1.3.1 вердикт читается напрямую с `MultiPlayerGameMode#useItemOn`,
у которого ровно две точки возврата, обе известны:

```
27: areturn    InteractionResult.FAIL   — клик вне границы мира
67: areturn    результат startPrediction(...)  — SUCCESS / CONSUME / PASS / FAIL / null
```

`null` на offset 67 — не защитная фантазия: значение достаётся из `MutableObject`, который пишет
предсказанное действие, и если оно не выполнялось, писать будет некому. Поэтому он и сообщается как
`null`, а не приводится к `FAIL`.

`placed` растёт, когда Easy Place вышел через ordinal 8 **и** последний `useItemOn` ответил `SUCCESS`.
`refused` — то же самое, но ответ был `FAIL`/`CONSUME`/`null`: клик ушёл, ванилла его не приняла.
Это то число, которое поймало бы перевёрнутый счётчик сразу.

`[CLICK]` печатает всё это прямо в строке:

```
[CLICK] post-rewrite -> handleEasyPlace returned Pass (areturn ordinal 8)  |  BLOCK PLACED
        [placed=19 refused=0 blocked=180 passed=47]
```

`passed` — клики, дошедшие до неотказной точки возврата без постановки блока (ordinal 2 — цель
схематики оказалась воздухом, и ordinal 7 — `useItemOn` сказал `PASS`). Это не поломки.

### Настройки

`config/easyplace_lightfix.properties`:

| ключ | по умолчанию | что делает |
|---|---|---|
| `debug` | `true` | все диагностики |
| `logToFile` | `true` | дубль в `logs/easyplace_lightfix.log` |
| `traceThrottleMs` | `1500` | пауза между одинаковыми `[TRACE]` и `[CLICK]` |
| `snapshotThrottleMs` | `1500` | пауза между `[BLOCKED]` |
| `unthrottledSnapshots` | `3` | сколько первых снимков пишется без троттлинга |
| `rotateForLightBlocks` | `true` | разворот Printer при клике по light block. Видно серверу. Работает и когда клик делает EasyPlaceFix |
| `rescueLightTarget` | `true` | возврат цели, потерянной из-за ванильного блока на луче. Только переписанный путь |
| `directClickForLightBlocks` | `true` | клик прямо по клетке света вместо ответа Litematica. Не зависит от `easyPlaceClickAdjacent`. **Выключить = на переписанном пути свет в воздухе поставить нельзя** |

Сброс конфига при обновлении не обязателен: все ключи, кроме `directClickForLightBlocks`, не меняли
ни в 1.2.1, ни в 1.3.0.

### Если игра падает с `MixinApplyError` на `MixinEasyPlaceUtilsDelivery` или `MixinMultiPlayerGameModeDelivery`

Эти два mixin'а — **поведение**, а не диагностика, поэтому они в конфиге с `"required": true`, и
поломка Injector'а валит игру, а не проходит тихо. Так и задумано: «light block опять не ставится» —
плохое состояние, лучше явный стек.

* `MixinEasyPlaceUtilsDelivery` — целится в `getTargetPosition` и `getClickPosition`;
* `MixinMultiPlayerGameModeDelivery` — целится в `MultiPlayerGameMode#useItemOn`: разворот на `HEAD`
  (нужен только он) и чтение вердикта клика на ordinal'ах `RETURN` 0 и 1. Если упадёт именно этот
  класс, потеряется и разворот, и `placed`/`refused`, но сам свет продолжит ставиться.

С 1.3.1 у `useItemOn` пиннятся **обе** точки возврата, поэтому изменение их числа (а не только
переезд метода) сделает этот класс неприменимым — и, в отличие от диагностики, уронит игру.
Правильное число — две, на offset'ах `27` и `67`.

Чаще всего причина — переехавшие в Litematica методы: `getTargetPosition`, `getClickPosition`,
`handleEasyPlace` или `MultiPlayerGameMode#useItemOn`. Проверьте, что они на месте:

```powershell
javap -p -c -cp .\litematica-fabric-26.2-0.28.8.jar fi.dy.masa.litematica.util.EasyPlaceUtils
```

Нужен `getTargetPosition`, возвращающий `BlockHitResult`, `getClickPosition` с ровно **двумя**
`areturn` (offset'ы 18 и 47 — вторая и есть цель хука), и `handleEasyPlace()Lnet/minecraft/world/InteractionResult;`
с ровно **девятью** `areturn` на offset'ах `79, 123, 127, 185, 231, 276, 580, 955, 959`. Диагностические
хуки пиннят ordinal'ы по номерам, поэтому изменение числа точек возврата сделает их бессильными — но
они в `required: false`-конфиге, и мод продолжит работать.

### Если игра падает с `InvalidInjectionException`

```
non-static callback method ...::notoshi$lightBlockIsSolidForTrace targets a static method which is not supported
```

Значит обработчик mixin'а объявлен без `static`, а целевой метод Litematica — статический.
`RayTraceUtils`, `WorldUtils` и `EasyPlaceUtils` в Litematica — это utility-классы, состоящие
целиком из статических методов, поэтому **все** обработчики в этом моде обязаны быть `static`.

### Если игра падает с `CancellationException`

```
org.spongepowered.asm.mixin.injection.callback.CancellationException:
    The call <method> is not cancellable.
	at CallbackInfoReturnable.setReturnValue(...)
```

Значит хук вызывает `cir.setReturnValue(...)`, а `@Inject` забыли объявить `cancellable = true`.
Это **именно та ошибка, которая роняла игру в 1.2.0** (см. раздел «Что было сломано в 1.2.0»).
Она не роняет игру сразу при запуске, а только в момент срабатывания хука — то есть посреди сессии,
на первом же клике, для которого хук что-то возвращает.

### Если игра ругается `Invalid descriptor ... Expected (..., CallbackInfo)V`

```
InvalidInjectionException: Invalid descriptor on ...notoshi$debugLegacyTick
Expected (Lnet/minecraft/client/Minecraft;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V
but found (Lnet/minecraft/client/Minecraft;)V
```

Mixin **всегда** дописывает `CallbackInfo` в конец сигнатуры обработчика — даже для `@Inject` в
метод с возвратом `void`. Забытый параметр валидатор не примет.

Если целевой метод **возвращает значение**, нужен именно `CallbackInfoReturnable`, а не `CallbackInfo`,
даже для `@Inject(method = ..., at = @At("HEAD"))`:

```
Expected (Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V but found ()V
```

Важная деталь: такая ошибка отменяет применение **всего класса** mixin'а, то есть молча выключает
все остальные хуки этого класса. Игра при этом не падает, потому что диагностический mixin-конфиг
помечен `"required": false` — ошибка приходит просто как `WARN` в `logs/latest.log`.

Практический вывод: **молчащая диагностика — это не «всё хорошо», это сломанный mixin.** Ищите
`Mixin apply for mod easyplace_lightfix failed` в `logs/latest.log` первым делом, если в
`logs/easyplace_lightfix.log` пусто. Похожее уже случалось трижды: в 1.0.1 так отвалились все
диагностики `WorldUtils`, в 1.1.0 — весь `MixinEasyPlaceUtilsDebug`, в 1.3.0 — оба отладочных класса
одновременно.

### Если игра ругается `Found N unexpected additional method arguments`

```
Found 1 unexpected additional method arguments: (boolean)
```

Обработчик `@Redirect` объявил возвращаемое значение цели **лишним параметром**. Для статической цели
Mixin передаёт его как тип возврата обработчика, а не как аргумент, и обойти это нельзя: чтобы узнать
исходный вердикт, обработчик должен вызвать целевой метод — а это немедленно перевходит в тот же
`@Redirect`. Единственный работающий вариант — пиннятая `@Inject` на каждой точке возврата.

Подробности и обе настоящие ошибки из лога — в разделе «Как сделано так, чтобы диагностика не врала».

Отличать это от `Invalid descriptor` не нужно: обе ошибки означают одно и то же — обработчик
написан не по той сигнатуре, которую ждёт Mixin, и весь класс mixin'а молча выключается.

### Про `NoSuchMethodException: AirBlock.useWithoutItem` в логе

```
[Render thread/WARN]: EasyPlaceUtils: Failed to reflect method Block::useWithoutItem
java.lang.NoSuchMethodException: net.minecraft.world.level.block.AirBlock.useWithoutItem(...)
	at fi.dy.masa.litematica.util.EasyPlaceUtils.hasUseAction(EasyPlaceUtils.java:99)
```

Это **Litematica** рефлектит метод, которого в 26.2 нет. Стек трейса проходит через
`fi.dy.masa.litematica.util.EasyPlaceUtils`, а не через наш код. На работу мода не влияет, это
баг Litematica — оставлен как есть, чтобы вы не искали его у себя.

## Обновление на новую Litematica

Дескриптор в mixin привязан к версии Minecraft:

```
BlockState.getShape(BlockGetter, BlockPos, CollisionContext)
```

Это **не менялось** начиная с 1.21 и по 26.2 включительно. Ломается он только если Mojang поменяет
сигнатуру метода. Методы Litematica `traceFirstStep` и `traceLoopSteps` тоже могут переехать — тогда
сборка не сломается, но игра выдаст:

```
Mixin apply failed. ... No such method: traceFirstStep
```

Быстрая проверка после обновления Litematica:

```powershell
# посмотреть, есть ли нужные методы и вызов getShape в актуальном jar
javap -c -p <путь-к-fi/dy/masa/litematica/util/RayTraceUtils.class> | Select-String 'getShape'

# и пересчитать точки возврата handleEasyPlace: должно быть 9
javap -p -c -cp .\litematica-fabric-26.2-0.28.8.jar fi.dy.masa.litematica.util.EasyPlaceUtils |
    Select-String 'areturn'
```

Если методов больше нет или они переименованы — поправьте список в
`@Mixin(targets = "...")` / `@Redirect(method = { ... })`.

Запасной вариант: в `src/main/resources/easyplace_lightfix.mixins.json` поставить
`"required": false` — тогда мод не будет ронять игру, а просто молча ничего не сделает.

## Сборка из исходников

См. [BUILD.md](BUILD.md).

## Лицензия

MIT