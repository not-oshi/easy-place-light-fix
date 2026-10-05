# Сборка Easy Place Light Fix

Мод уже собран: **`build/libs/easyplace_lightfix-26.2-1.0.0.jar`** — его достаточно скопировать в `.minecraft/mods/`.

Ниже — как пересобрать самому.

## Требования

* **JDK 25** (Minecraft 26.x собирается под Java 25). Проверить: `java -version`
* Интернет при первой сборке — Loom скачает Minecraft и зависимости
* Gradle **не нужен**: в проекте есть `gradlew` / `gradlew.bat` и `gradle/wrapper/`

`JAVA_HOME` должен указывать на JDK 25, иначе сборка упадёт с
`invalid source release: 25`.

## Сборка

```powershell
cd C:\Users\not_o\easy-place-light-fix

.\gradlew.bat build
```

Готовый файл: `build\libs\easyplace_lightfix-26.2-1.0.0.jar`

Только пересобрать jar без запуска тестов:

```powershell
.\gradlew.bat build -x test
```

## Запуск dev-клиента (необязательно)

Litematica намеренно не подключена как compile-зависимость, поэтому для запуска игры её нужно
положить вручную:

1. Скачать с [Modrinth](https://modrinth.com/mod/litematica/version/0.28.8):
   * `litematica-fabric-26.2-0.28.8.jar`
   * malilib 0.29.6 (`malilib-fabric-26.2-0.29.6.jar`) с
     [CurseForge](https://www.curseforge.com/minecraft/mc-mods/malilib/files) или Maven
2. Положить оба jar в `C:\Users\not_o\easy-place-light-fix\libs\`
3. Раскомментировать блок `loom { runs { client { ... } } }` в `build.gradle`
4. `.\gradlew.bat runClient`

> Если запустить dev-клиент **без** Litematica в `libs/`, игра упадёт на старте: mixin-конфиг
> объявлен как `"required": true`. Для обычной игры в вашем инстансе это не относится — там
> Litematica всегда есть.

## Чистка

```powershell
.\gradlew.bat clean
```

## Если что-то сломалось

**`Missing property (mod_id) for Groovy template expansion`**
В `build.gradle` в блоке `processResources` перечислены не все ключи, которые используются в
`fabric.mod.json`. Сейчас там `mod_id`, `mod_version`, `minecraft_version` — все три должны быть
в `expand` и в `inputs.property`.

**`release version 25 not supported`**
Не та Java. Нужен именно JDK 25.

**`Mixin apply failed ... No such method: traceFirstStep`**
Обновилась Litematica и целевые методы переехали. См. раздел «Обновление на новую Litematica» в
[README.md](README.md).

**Не помогло в игре, ошибок нет**
Проверьте лог: должна быть строка `Easy Place Light Fix loaded: ...`. Если есть предупреждение
`but Litematica was not found` — Litematica не загрузилась.