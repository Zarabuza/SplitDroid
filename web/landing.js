const services = [
  "Сбербанк", "Т-Банк", "Госуслуги", "ВТБ", "Альфа-Банк",
  "Ozon", "Wildberries", "Яндекс", "Авито", "МТС",
  "Билайн", "Мегафон", "Почта России", "РЖД", "СберМаркет",
  "Delivery Club", "Яндекс Еда", "Ситилинк", "DNS", "М.Видео"
];

const root = document.getElementById("chips");
if (root) {
  services.forEach((name, i) => {
    const el = document.createElement("span");
    el.className = "chip";
    el.textContent = name;
    el.style.animationDelay = `${0.04 * i}s`;
    root.appendChild(el);
  });
}
