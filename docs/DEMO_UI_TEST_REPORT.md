# 上线演示前 UI 视觉测试报告

**测试日期**: 2026-07-22（演示前一天）
**方式**: 内置浏览器逐页真实操作走查（桌面 1280×720 + 移动 375×812），所有结论均为实际复现结果
**环境**: 前端 vite:5173 · 后端 :8080（MAIL_ENABLED=false，UPLOAD_DIR 绝对路径）· MySQL/Redis 本机 · DeepSeek key 已启用

---

## 一、测试结果总览

| # | 页面/流程 | 检查点 | 结果 |
|---|---|---|---|
| 1 | Landing `/` | 深色主题、Aurora 动效、滚动渐显、双 CTA、无 Navbar/Chat | ✅ |
| 2 | Landing 移动端 | hero 标题溢出 | ❌→✅ 已修 (P7) |
| 3 | Register `/register` | 表单、非 UKM 邮箱拒绝提示 | ✅ |
| 4 | OTP `/verify-otp` | 邮箱预填、错误码提示、正确码→自动登录 | ✅ |
| 5 | Login `/login` | 错误态提示、成功跳转 | ✅（错误色已修 P1） |
| 6 | Marketplace 关键词搜索 | "hoodie" → 1 结果，统计卡实时更新 | ✅ |
| 7 | Marketplace AI 语义搜索 | "something to keep me warm on campus" → Hoodie + Columbia 夹克 + AI 提示条 | ✅ |
| 8 | 空结果态 | 'NO RESULTS FOR "QWERTYZZZ".' | ✅ |
| 9 | 分类 chips | Electronics → 17 个挂牌（与数据一致） | ✅ |
| 10 | ProductDetail `/product/41` | 徽标、库存、加购（徽标+1、绿色提示） | ✅ |
| 11 | ItemDetail `/item/69` | C2C·LIKE_NEW 徽标、AVAILABLE | ✅ |
| 12 | 评论 ReviewsPanel | 未购买→拒绝并提示；购买后→发布成功、汇总 4.0/5.0 | ✅ |
| 13 | Cart `/cart` | 商品行、总价、双支付方式、下单 | ✅ |
| 14 | Checkout | "ALL ORDERS CONFIRMED" + PAID + 徽标清零 | ✅ |
| 15 | Orders `/orders` `/orders/87` | 统计卡、列表、详情（支付名已修 P4） | ✅ |
| 16 | Sell `/sell` | 表单、URL 图片回退、发布→挂牌数 32→33 | ✅ |
| 17 | SecKill `/seckill` | live/upcoming/ended 三区、倒计时每秒走、按钮三态 | ✅ |
| 18 | SecKill 抢购 | "Processing…" → "Secured! Order #88 confirmed."（约 5s 异步确认） | ✅ |
| 19 | SecKill 限购 | 重复点击 → "Limit: 1 per user." | ✅ |
| 20 | ChatWidget | 打开、"Calling tools" 指示器、回复含动作卡片 | ✅ |
| 21 | Chat 动作卡片 | "+ Cart" 真实加购（徽标 0→1） | ✅ |
| 22 | Chat 实时数据 | 闪购提问 → 剩余 14 件（准确）、起止时间正确 | ✅ |
| 23 | 守卫：未登录 `/sell` | → `/login` | ✅ |
| 24 | 守卫：买家 `/admin` | → `/` | ✅ |
| 25 | 买家导航栏 | 正确隐藏 ADMIN 入口 | ✅ |
| 26 | Profile `/profile` | 统计、双 Tab、挂牌行 | ✅ |
| 27 | AdminDashboard | 建秒杀活动 ×2、成功提示、下拉/时间控件 | ✅ |
| 28 | 移动端 Marketplace | 统计卡 2×2、双列网格、导航收纳 | ✅ |
| 29 | Console | 全程无报错（仅 React Router v7 迁移警告，可忽略） | ✅ |

## 二、发现的问题与修复记录

| # | 问题 | 严重度 | 处理 |
|---|---|---|---|
| P0 | **图片全裂**：后端 `upload.dir` 是相对路径，从 `backend/` 启动时读不到根目录 `uploads/`（39 个文件） | 🔴 演示杀手 | ✅ 已合并两处文件（49 个）；启动脚本固定 `UPLOAD_DIR` 绝对路径（见下方晨检） |
| P1 | 登录/注册/OTP 错误提示为品牌蓝，不像错误 | 🟡 | ✅ 新增 `--danger:#dc2626`，三处改红（Login/Register/OtpVerify.tsx） |
| P2 | AI Search 开关命中区仅 32×16px 药丸，点文字无效 | 🟡 | ✅ onClick 移到整个 label（Marketplace.tsx） |
| P3 | 页脚 "FYP 2025" 与 Landing "FYP 2026" 不一致 | 🟢 | ✅ 统一 2026（Layout.tsx） |
| P4 | 订单详情支付方式显示内部枚举 "FAKE_WALLET" | 🟡 评审观感 | ✅ 映射为 "Campus Wallet"（OrderDetail.tsx） |
| P5 | 聊天气泡 Markdown 不渲染（`**` 裸显）| 🟡 | ✅ ChatWidget 加 renderRich()（仅 **bold**/`code`，无 dangerouslySetInnerHTML） |
| P6 | LLM 偶发马来语回复英文问题 + 输出 Markdown 表格 | 🟡 | ✅ ChatService 系统提示加语言跟随 + 禁表格约束（已重启验证） |
| P7 | 移动端 Landing hero "INFRASTRUCTURE." 溢出被裁 | 🟡 | ✅ 字号改 `text-[2rem] sm:text-[3.1rem] md:text-8xl`（Landing.tsx） |
| P8 | **上传本地图后无法发布商品**：`ImageUpload` 的粘贴框是 `<input type="url">`，上传返回相对路径 `/uploads/xxx.jpg` 过不了浏览器原生 URL 校验，`form.checkValidity()=false`→整个表单被拦，提示 "Please enter a URL"。**同时影响学生挂牌(SellItem)和管理员建商品(AdminDashboard)** | 🔴 核心功能不可用 | ✅ 改为 `type="text" inputMode="url"`（ImageUpload.tsx）；端到端复测：相对路径图片成功建挂牌 #102（已删） |

### 遗留观察（不影响演示，未改动）
- 聊天动作卡片会列出工具返回的**全部**搜索结果（问耳机也带出 MacBook 等 6 张卡），与文字推荐不完全对应。建议后续只渲染 AI 点名的商品。
- 秒杀成功后按钮回到可点状态（再点会被限购拦截，逻辑正确，仅按钮态可再优化为 "Secured ✓"）。
- PageSpotlight/Crosshair/SpotlightCard 特效仅在鼠标（fine-pointer）+ 非 reduced-motion 环境显示——投影仪+鼠标正常，触屏设备不显示属设计预期。
- 测试造的数据：买家 `demo.buyer@siswa.ukm.edu.my / Buyer@123`（已验证，可直接用于演示）、订单 #87/#88、Hoodie 一条 4★ 评论、挂牌 "UI Test Listing — Study Desk Lamp"（若嫌碍眼可在演示前删除/忽略）。

## 三、明日演示晨检清单（约 10 分钟）

```bash
# 1) 启动后端（必须带 UPLOAD_DIR，否则图片全裂！）
cd ~/Documents/FYP/backend && set -a && source ../.env && set +a && \
  export MAIL_ENABLED=false UPLOAD_DIR=~/Documents/FYP/uploads \
  JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn spring-boot:run
```

```bash
# 2) 启动前端
npm --prefix ~/Documents/FYP/frontend run dev
```

3. 打开 http://localhost:5173 → Landing 深色页正常、点 ENTER MARKETPLACE
4. Marketplace 商品**图片全部显示**（这是 P0 的回归检查点）
5. admin@ukm.edu.my / Admin@123 登录 → /admin 可进
6. AI Search 开关打开 → 搜 "something to keep me warm on campus" → 出 Hoodie
7. 聊天助手问 "is there any flash sale right now?" → 回复带实时库存 + 动作卡片
8. /seckill 倒计时在走、Hoodie 活动 LIVE（活动 #13 至 7/23 23:59；#14 明天 15:00 开始）
9. 演示注册流程时：OTP 在**后端控制台日志**里（`[OTP] (mail disabled) code for ...`）
10. 建议演示分辨率 1280×720 以上；手机演示 Landing 已适配

## 四、演示账号速查

| 角色 | 邮箱 | 密码 | 备注 |
|---|---|---|---|
| 管理员 | admin@ukm.edu.my | Admin@123 | 可进 /admin |
| 买家 | demo.buyer@siswa.ukm.edu.my | Buyer@123 | 已有订单/评论/秒杀记录 |
