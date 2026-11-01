import asyncio
import logging
import os
import sqlite3
import time
import uuid
from html import escape

from aiogram import Bot, Dispatcher, F, Router
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.exceptions import TelegramBadRequest
from aiogram.filters import Command, CommandObject, CommandStart
from aiogram.types import CallbackQuery, Message
from aiogram.utils.keyboard import InlineKeyboardBuilder

TOKEN = os.environ["BOT_TOKEN"]
SUPPORT = "@mbit_support"
DB_PATH = os.environ.get("DB_PATH", "mbit.db")

# ---------------------------------------------------------------- данные
PLANS = {
    "basic": {"name": "Basic", "days": 30, "price": 149, "devices": 2, "speed": "до 100 Мбит/с",
              "locs": ["nl", "de", "fi"]},
    "standard": {"name": "Standard", "days": 90, "price": 399, "devices": 5, "speed": "до 500 Мбит/с",
                 "locs": ["nl", "de", "fi", "pl", "us"]},
    "premium": {"name": "Premium", "days": 365, "price": 1290, "devices": 10, "speed": "без ограничений",
                "locs": ["nl", "de", "fi", "pl", "us", "tr", "jp"]},
}
TRIAL_DAYS = 1
REF_BONUS_DAYS = 3

LOCATIONS = {
    "nl": {"flag": "🇳🇱", "name": "Нидерланды", "ping": 38, "load": 42, "host": "nl.mbitvpn.example"},
    "de": {"flag": "🇩🇪", "name": "Германия", "ping": 33, "load": 55, "host": "de.mbitvpn.example"},
    "fi": {"flag": "🇫🇮", "name": "Финляндия", "ping": 45, "load": 28, "host": "fi.mbitvpn.example"},
    "pl": {"flag": "🇵🇱", "name": "Польша", "ping": 29, "load": 61, "host": "pl.mbitvpn.example"},
    "us": {"flag": "🇺🇸", "name": "США", "ping": 120, "load": 35, "host": "us.mbitvpn.example"},
    "tr": {"flag": "🇹🇷", "name": "Турция", "ping": 55, "load": 47, "host": "tr.mbitvpn.example"},
    "jp": {"flag": "🇯🇵", "name": "Япония", "ping": 190, "load": 22, "host": "jp.mbitvpn.example"},
}
TRIAL_LOCS = ["nl", "de"]

GUIDES = {
    "android": "🤖 <b>Android</b>\n1. Установи <b>v2rayNG</b> или <b>Hiddify</b> из Google Play.\n2. Нажми «Скопировать ключ» в боте.\n3. В приложении: + → «Импорт из буфера».\n4. Включи подключение.",
    "ios": "🍏 <b>iOS</b>\n1. Установи <b>Streisand</b> или <b>V2Box</b> из App Store.\n2. Скопируй ключ из бота.\n3. В приложении добавь конфиг из буфера обмена.\n4. Включи VPN.",
    "windows": "🪟 <b>Windows</b>\n1. Скачай <b>Hiddify</b> или <b>v2rayN</b>.\n2. Скопируй ключ из бота.\n3. Вставь из буфера обмена в приложение.\n4. Подключись.",
    "macos": "🍎 <b>macOS</b>\n1. Установи <b>Hiddify</b> или <b>V2Box</b>.\n2. Скопируй ключ из бота.\n3. Импортируй из буфера и подключись.",
}

# ---------------------------------------------------------------- база
db = sqlite3.connect(DB_PATH)
db.row_factory = sqlite3.Row
db.execute(
    """CREATE TABLE IF NOT EXISTS users(
        id INTEGER PRIMARY KEY, username TEXT, created INTEGER,
        plan TEXT, expires INTEGER DEFAULT 0, location TEXT DEFAULT 'nl',
        uuid TEXT, trial_used INTEGER DEFAULT 0, referrer INTEGER, refs INTEGER DEFAULT 0)"""
)
db.commit()


def get_user(uid):
    return db.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()


def ensure_user(uid, username, referrer=None):
    u = get_user(uid)
    if u:
        return u, False
    if referrer == uid or (referrer and not get_user(referrer)):
        referrer = None
    db.execute(
        "INSERT INTO users(id, username, created, uuid, referrer) VALUES(?,?,?,?,?)",
        (uid, username, int(time.time()), str(uuid.uuid4()), referrer),
    )
    db.commit()
    return get_user(uid), True


def add_days(uid, days, plan=None):
    u = get_user(uid)
    base = max(u["expires"], int(time.time()))
    new = base + days * 86400
    if plan:
        db.execute("UPDATE users SET expires=?, plan=? WHERE id=?", (new, plan, uid))
    else:
        db.execute("UPDATE users SET expires=? WHERE id=?", (new, uid))
    db.commit()


def is_active(u):
    return u["expires"] > time.time()


def fmt_date(ts):
    return time.strftime("%d.%m.%Y %H:%M", time.localtime(ts))


def days_left(u):
    return max(0, int((u["expires"] - time.time()) // 86400) + 1) if is_active(u) else 0


def allowed_locs(u):
    if not is_active(u):
        return []
    if u["plan"] in PLANS:
        return PLANS[u["plan"]]["locs"]
    return TRIAL_LOCS


def make_key(u):
    loc = LOCATIONS[u["location"]]
    # Заглушка. Для реальной работы подставь данные своей панели (Marzban / 3x-ui и т.д.)
    return (f"vless://{u['uuid']}@{loc['host']}:443?type=tcp&security=reality"
            f"#MBit-VPN-{loc['name']}")


# ---------------------------------------------------------------- клавиатуры
def main_kb():
    b = InlineKeyboardBuilder()
    b.button(text="💎 Тарифы", callback_data="plans")
    b.button(text="🌍 Локации", callback_data="locs")
    b.button(text="👤 Профиль", callback_data="profile")
    b.button(text="🔑 Мой ключ", callback_data="key")
    b.button(text="📖 Инструкции", callback_data="guides")
    b.button(text="🎁 Рефералы", callback_data="ref")
    b.button(text="💬 Поддержка", callback_data="support")
    b.adjust(2, 2, 2, 1)
    return b.as_markup()


def back_kb(extra=None):
    b = InlineKeyboardBuilder()
    for text, cb in (extra or []):
        b.button(text=text, callback_data=cb)
    b.button(text="⬅️ В меню", callback_data="menu")
    b.adjust(1)
    return b.as_markup()


# ---------------------------------------------------------------- тексты
def menu_text(u):
    status = f"✅ активна до {fmt_date(u['expires'])}" if is_active(u) else "❌ не активна"
    return (f"🚀 <b>MBit VPN</b> — быстрый и безопасный VPN\n\n"
            f"Подписка: {status}\n\nВыбери раздел:")


router = Router()


async def show(cb: CallbackQuery, text, kb):
    try:
        await cb.message.edit_text(text, reply_markup=kb)
    except TelegramBadRequest:
        pass
    await cb.answer()


# ---------------------------------------------------------------- хендлеры
@router.message(CommandStart())
async def start(m: Message, command: CommandObject):
    ref = None
    if command.args and command.args.startswith("ref"):
        try:
            ref = int(command.args[3:])
        except ValueError:
            pass
    u, new = ensure_user(m.from_user.id, m.from_user.username, ref)
    if new and u["referrer"]:
        add_days(u["referrer"], REF_BONUS_DAYS)
        db.execute("UPDATE users SET refs=refs+1 WHERE id=?", (u["referrer"],))
        db.commit()
        try:
            await m.bot.send_message(u["referrer"], f"🎁 По твоей ссылке пришёл друг! +{REF_BONUS_DAYS} дн. к подписке.")
        except Exception:
            pass
    await m.answer(menu_text(get_user(m.from_user.id)), reply_markup=main_kb())


@router.message(Command("menu"))
async def menu_cmd(m: Message):
    u, _ = ensure_user(m.from_user.id, m.from_user.username)
    await m.answer(menu_text(u), reply_markup=main_kb())


@router.callback_query(F.data == "menu")
async def menu(cb: CallbackQuery):
    u, _ = ensure_user(cb.from_user.id, cb.from_user.username)
    await show(cb, menu_text(u), main_kb())


# ---- тарифы
@router.callback_query(F.data == "plans")
async def plans(cb: CallbackQuery):
    u, _ = ensure_user(cb.from_user.id, cb.from_user.username)
    text = "💎 <b>Тарифные планы MBit VPN</b>\n\n"
    if not u["trial_used"]:
        text += f"🆓 <b>Пробный</b> — {TRIAL_DAYS} день бесплатно\n\n"
    b = InlineKeyboardBuilder()
    for pid, p in PLANS.items():
        per_month = round(p["price"] / (p["days"] / 30))
        text += (f"<b>{p['name']}</b> — {p['price']} ₽ / {p['days']} дн.\n"
                 f"   📱 устройств: {p['devices']} · ⚡ {p['speed']}\n"
                 f"   🌍 локаций: {len(p['locs'])} · ≈ {per_month} ₽/мес\n\n")
        b.button(text=f"{p['name']} · {p['price']} ₽", callback_data=f"buy:{pid}")
    if not u["trial_used"]:
        b.button(text="🆓 Попробовать бесплатно", callback_data="trial")
    b.button(text="⬅️ В меню", callback_data="menu")
    b.adjust(1)
    await show(cb, text + "<i>Тестовый режим: тариф активируется бесплатно.</i>", b.as_markup())


@router.callback_query(F.data == "trial")
async def trial(cb: CallbackQuery):
    u, _ = ensure_user(cb.from_user.id, cb.from_user.username)
    if u["trial_used"]:
        return await cb.answer("Пробный период уже использован", show_alert=True)
    add_days(u["id"], TRIAL_DAYS, "trial")
    db.execute("UPDATE users SET trial_used=1 WHERE id=?", (u["id"],))
    db.commit()
    await show(cb, f"🎉 Пробный период на {TRIAL_DAYS} дн. активирован!\nЗабери ключ 👇",
               back_kb([("🔑 Получить ключ", "key")]))


@router.callback_query(F.data.startswith("buy:"))
async def buy(cb: CallbackQuery):
    pid = cb.data.split(":")[1]
    p = PLANS[pid]
    ensure_user(cb.from_user.id, cb.from_user.username)
    add_days(cb.from_user.id, p["days"], pid)
    u = get_user(cb.from_user.id)
    if u["location"] not in p["locs"]:
        db.execute("UPDATE users SET location=? WHERE id=?", (p["locs"][0], u["id"]))
        db.commit()
    await show(cb, f"✅ Тариф <b>{p['name']}</b> активирован (тест) до {fmt_date(u['expires'])}.",
               back_kb([("🔑 Получить ключ", "key"), ("🌍 Выбрать локацию", "locs")]))


# ---- локации
@router.callback_query(F.data == "locs")
async def locs(cb: CallbackQuery):
    u, _ = ensure_user(cb.from_user.id, cb.from_user.username)
    ok = allowed_locs(u)
    text = "🌍 <b>Локации серверов</b>\n\n"
    b = InlineKeyboardBuilder()
    for lid, l in LOCATIONS.items():
        dot = "🟢" if l["load"] < 40 else "🟡" if l["load"] < 65 else "🔴"
        lock = "" if (lid in ok or not is_active(u)) else " 🔒"
        cur = " ✔️" if lid == u["location"] else ""
        text += f"{l['flag']} <b>{l['name']}</b>{cur}{lock} — {l['ping']} мс {dot} {l['load']}%\n"
        b.button(text=f"{l['flag']} {l['name']}{cur}", callback_data=f"loc:{lid}")
    b.button(text="⬅️ В меню", callback_data="menu")
    b.adjust(2)
    await show(cb, text + "\n🔒 — доступно на более высоком тарифе.", b.as_markup())


@router.callback_query(F.data.startswith("loc:"))
async def set_loc(cb: CallbackQuery):
    lid = cb.data.split(":")[1]
    u, _ = ensure_user(cb.from_user.id, cb.from_user.username)
    if not is_active(u):
        return await cb.answer("Сначала активируй тариф или пробный период", show_alert=True)
    if lid not in allowed_locs(u):
        return await cb.answer("Эта локация недоступна на твоём тарифе 🔒", show_alert=True)
    db.execute("UPDATE users SET location=? WHERE id=?", (lid, u["id"]))
    db.commit()
    await cb.answer(f"Выбрано: {LOCATIONS[lid]['name']}")
    await locs(cb)


# ---- профиль
@router.callback_query(F.data == "profile")
async def profile(cb: CallbackQuery):
    u, _ = ensure_user(cb.from_user.id, cb.from_user.username)
    l = LOCATIONS[u["location"]]
    if is_active(u):
        name = PLANS[u["plan"]]["name"] if u["plan"] in PLANS else "Пробный"
        sub = f"✅ {name}\n⏳ до {fmt_date(u['expires'])} (осталось {days_left(u)} дн.)"
    else:
        sub = "❌ нет активной подписки"
    text = (f"👤 <b>Профиль</b>\n\n"
            f"🆔 ID: <code>{u['id']}</code>\n"
            f"📛 Username: @{escape(u['username'] or '—')}\n"
            f"📅 Регистрация: {fmt_date(u['created'])}\n\n"
            f"💎 Подписка: {sub}\n"
            f"📍 Локация: {l['flag']} {l['name']}\n"
            f"👥 Приглашено друзей: {u['refs']}")
    await show(cb, text, back_kb([("💎 Тарифы", "plans"), ("🌍 Сменить локацию", "locs")]))


# ---- ключ
@router.callback_query(F.data == "key")
async def key(cb: CallbackQuery):
    u, _ = ensure_user(cb.from_user.id, cb.from_user.username)
    if not is_active(u):
        return await show(cb, "❌ Нет активной подписки.", back_kb([("💎 Тарифы", "plans")]))
    l = LOCATIONS[u["location"]]
    await show(cb, f"🔑 <b>Твой ключ</b> ({l['flag']} {l['name']})\n\n<code>{make_key(u)}</code>\n\n"
                   "Нажми на ключ, чтобы скопировать. Инструкции — в разделе «Инструкции».",
               back_kb([("📖 Инструкции", "guides")]))


# ---- инструкции
@router.callback_query(F.data == "guides")
async def guides(cb: CallbackQuery):
    b = InlineKeyboardBuilder()
    for k, t in (("android", "🤖 Android"), ("ios", "🍏 iOS"), ("windows", "🪟 Windows"), ("macos", "🍎 macOS")):
        b.button(text=t, callback_data=f"guide:{k}")
    b.button(text="⬅️ В меню", callback_data="menu")
    b.adjust(2, 2, 1)
    await show(cb, "📖 <b>Выбери платформу:</b>", b.as_markup())


@router.callback_query(F.data.startswith("guide:"))
async def guide(cb: CallbackQuery):
    await show(cb, GUIDES[cb.data.split(":")[1]], back_kb([("📖 Другие платформы", "guides")]))


# ---- рефералы
@router.callback_query(F.data == "ref")
async def ref(cb: CallbackQuery):
    u, _ = ensure_user(cb.from_user.id, cb.from_user.username)
    me = await cb.bot.get_me()
    await show(cb, f"🎁 <b>Реферальная программа</b>\n\nЗа каждого друга — <b>+{REF_BONUS_DAYS} дня</b> подписки.\n\n"
                   f"Твоя ссылка:\n<code>https://t.me/{me.username}?start=ref{u['id']}</code>\n\n"
                   f"Приглашено: {u['refs']}", back_kb())


# ---- поддержка
@router.callback_query(F.data == "support")
async def support(cb: CallbackQuery):
    await show(cb, f"💬 <b>Поддержка</b>\n\nПиши: {SUPPORT}\nОтвечаем ежедневно 10:00–22:00.", back_kb())


async def main():
    logging.basicConfig(level=logging.INFO)
    bot = Bot(TOKEN, default=DefaultBotProperties(parse_mode=ParseMode.HTML))
    dp = Dispatcher()
    dp.include_router(router)
    # Не сбрасываем накопленные апдейты: во время перезапуска сообщения не теряются
    await bot.delete_webhook(drop_pending_updates=False)
    await dp.start_polling(bot)


if __name__ == "__main__":
    asyncio.run(main())
