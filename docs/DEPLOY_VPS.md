# FYP 云服务器部署速查(Ubuntu + Docker)

**目标**:把 FTSM E-Commerce 部署到腾讯云/阿里云轻量应用服务器,10 分钟内跑起来,AI 全部功能可用。

**推荐配置**:2vCPU / 4GB / 60GB SSD(2GB 内存也能跑,但需 build 时格外小心)
**推荐镜像**:**Ubuntu 24.04 LTS + Docker(预装)**——省去手动装 Docker 的一步

---

## 一、买服务器后要做的第一件事

拿到公网 IP 后,在**服务器控制台的防火墙/安全组**里开这些端口:
- **22** (SSH,登录用)
- **80** (HTTP,前端 nginx)
- **443** (HTTPS,以后加证书用,先开着)
- **8080** 不开(内部用,别对外)

MySQL 3306、Redis 6379 **绝对不要开公网**——本配置已让它们只在 Docker 内网通信。

---

## 二、SSH 登进服务器,一次跑完

```bash
# 1) 确认 Docker 已装好(选了 Docker 镜像的话直接就有)
docker --version && docker compose version

# 2) 拉代码
cd /root
git clone https://github.com/feniturema/FYP.git
cd FYP

# 3) 建 .env 并填真实密钥(见下方模板)
cp .env.example .env
nano .env    # 按下方"必填清单"逐项填写

# 4) 一键起服务(第一次 build ~5 分钟)
docker compose up -d --build

# 5) 看后端启动日志,直到看到 "Started FtsmEcommerceApplication"
docker compose logs -f backend
```

---

## 三、`.env` 必填清单(把 `你的服务器IP` 换成实际 IP)

```env
# ---- Database ----
DB_NAME=ftsm_ecommerce
DB_PASSWORD=换成一个强密码-至少16位

# ---- JWT (>=32 chars) ----
JWT_SECRET=换成一段长随机串-至少32位-别用示例值

# ---- CORS (改成你的公网 IP 或域名!否则前端调不通)----
CORS_ALLOWED_ORIGINS=http://你的服务器IP,http://你的域名

# ---- Mail(演示不需要真发邮件,保持 false;OTP 会打到后端日志)----
MAIL_ENABLED=false

# ---- AI: DeepSeek(聊天助手 + 语义搜索)----
LLM_API_KEY=sk-你的-DeepSeek-key
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_MODEL=deepseek-v4-flash

# ---- AI: OpenAI GPT-4o(拍照识图上架)----
OPENAI_API_KEY=sk-你的-OpenAI-key
OPENAI_VISION_MODEL=gpt-4o

# ---- Admin 种子账号 ----
SEED_ADMIN_EMAIL=admin@ukm.edu.my
SEED_ADMIN_PASSWORD=换成你自己的强密码
```

---

## 四、访问 + 冒烟测试

浏览器打开 **`http://你的服务器IP/`** — 应该看到 Landing 深色页。

登录:`admin@ukm.edu.my / 你在 .env 里设的密码`

冒烟顺序(每项都测,任何一项挂了说明配置有问题):
1. ✅ 首页 Landing 显示,点 ENTER MARKETPLACE
2. ✅ 商品图片**全部显示**(如果裂图,查后端 `docker compose logs backend | grep -i upload`)
3. ✅ AI Search 开关打开,搜 "warm campus" → 出 Hoodie(如果没反应,说明 `LLM_API_KEY` 没配对)
4. ✅ 点右下角聊天,发"laptop"→ 5 秒内有回复(同上,依赖 LLM_API_KEY)
5. ✅ /sell 页面上传张图,点"AI Smart Fill"→ 自动填表(依赖 `OPENAI_API_KEY`)
6. ✅ 加购 → 下单(FPX 100% 成功)→ 订单显示 PAID
7. ✅ /seckill 抢购 → "Secured! Order #XX confirmed."

---

## 五、常用运维命令

```bash
# 查看所有容器状态
docker compose ps

# 看某个服务日志(-f 跟随、--tail 100 只看最近 100 行)
docker compose logs -f backend
docker compose logs --tail 100 mysql

# 改完 .env 后重启(容器会读新的环境变量)
docker compose up -d

# 更新代码后重新构建
git pull
docker compose up -d --build

# 全部停止(数据保留在 docker volume 里)
docker compose down

# 全部停止 + 删数据(慎用!)
docker compose down -v
```

---

## 六、常见问题

**Q1: build 时内存不够被 kill?**
2GB 服务器容易撞上。方案:先本地 `docker compose build` 好镜像 → 用 `docker save` 打包 → `scp` 到服务器 → `docker load`。或者升 4GB。

**Q2: OTP 收不到怎么注册新用户?**
`MAIL_ENABLED=false` 时 OTP 打到后端日志:`docker compose logs backend | grep OTP`。演示用 seeded admin 账号就行,别注册新用户。

**Q3: AI 功能没反应?**
- `docker compose exec backend env | grep -E "LLM_|OPENAI_"` 确认变量真的进容器了
- `docker compose logs backend | grep -iE "llm|openai|deepseek"` 看有没有 401/403 报错(通常是 key 填错)

**Q4: 图片上传后裂图?**
本配置已用 `UPLOAD_DIR=/app/uploads` + docker volume,不会漂移。若真裂:`docker compose exec backend ls /app/uploads` 看文件是否落地。

**Q5: 想加 HTTPS?**
最快路径:域名 → Cloudflare 免费 DNS 代理 → 打开"Always HTTPS"。零改动,零证书。
