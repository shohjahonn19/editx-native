# EditX AI — Beat Sync Video Editor

Telefon yoki kompyuter brauzerida ishlaydigan, GitHub Pages'ga joylash mumkin bo‘lgan beta loyiha.

## Nimalar qiladi?
- Foydalanuvchi video va musiqa faylini tanlaydi.
- 15, 30 yoki 45 sekundlik edit tanlanadi.
- Musiqa audio tahlilidan taxminiy beat/transient nuqtalari aniqlanadi.
- Video turli vaqt qismlaridan olinib, ritmga yaqin kesishlar bilan bitta videoga yoziladi.
- Zoom, shake, flash va emoji-stiker overlaylari qo‘llanadi.
- Natija WebM formatida yuklab olinadi.
- Fayllar serverga yuborilmaydi; brauzerning o‘zida ishlanadi.

## GitHub'ga qo‘yish
1. GitHub repository'ni oching.
2. `index.html` faylini repository root'iga yuklang.
3. `Settings → Pages` bo‘limiga kiring.
4. `Deploy from a branch` tanlang, `main` va `/ (root)` belgilang.
5. Save bosing va Pages URL tayyor bo‘lishini kuting.

## Muhim cheklovlar
- Bu prototip “AI” deb nomlangan avtomatik beat-tahlilga ega, lekin bulutli generativ AI modelidan foydalanmaydi.
- Beat aniqlash taxminiy; har bir musiqada professional darajada to‘g‘ri bo‘lishi kafolatlanmaydi.
- Eksport WebM. MP4 eksporti uchun server tomonda FFmpeg yoki boshqa transkodlash yechimi kerak.
- Mobil brauzerda uzoq yoki katta videolar xotira/unumdorlik chekloviga uchrashi mumkin. Avval qisqa MP4 bilan sinab ko‘ring.
- Audio formatni brauzer dekodlay olishi kerak.
- GitHub Pages faqat hosting beradi; 24/7 server-side AI yoki FFmpeg jarayonini ishlatmaydi.

## Keyingi bosqich uchun
Professional versiyada server API, FFmpeg render pipeline, beat/onset analysis, transition presetlari, audio ducking, format/quality tanlash va xatolik loglari qo‘shilishi kerak.
