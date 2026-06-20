# 科大云盘 BGM 接入

目标：把 `assets/bgm/` 的 605MB 音乐库迁到科大云盘，通过 WebDAV + `rclone mount` 只读挂载到服务器，再让 Video Maker 从挂载目录读取。

## 方案选择

推荐默认方案：

- 科大云盘作为长期存储
- Linux 服务器使用 `rclone mount`
- Video Maker 通过 `BGM_DIR=/mnt/video-maker-bgm` 读取
- 打开 `--vfs-cache-mode full`，减少 FFmpeg 首次读远程文件的失败率

不建议：

- 直接把 WebDAV URL 暴露给应用层
- 不带 VFS 缓存地裸挂远程目录再交给 FFmpeg 实时读取

## 目录约定

- 挂载点：`/home/ubuntu/.mounts/video-maker-bgm`
- 缓存目录：`/home/ubuntu/.cache/rclone/video-maker-bgm`
- 远程目录：`ustc-bgm:Saves/video-maker-bgm`

## 一次性初始化

1. 安装 rclone

```bash
sudo apt update
sudo apt install -y rclone
```

2. 创建 WebDAV remote

```bash
rclone config
```

填写：

- `n` 新建 remote
- 名称：`ustc-bgm`
- Storage：`webdav`
- URL：使用你的科大云盘 WebDAV 地址
- Vendor：若无特别说明，先选 `other`
- User / Pass：填写科大云盘 WebDAV 用户名和密码

3. 测试连通

```bash
rclone lsd ustc-bgm:
```

4. 上传 BGM 目录

```bash
rclone mkdir ustc-bgm:Saves/video-maker-bgm
rclone copy /home/ubuntu/personal/video-maker-server/assets/bgm ustc-bgm:Saves/video-maker-bgm --progress
```

建议先保留本地 `assets/bgm/`，等服务切换成功后再删除本地副本。

## 服务端切换

1. 复制环境文件

```bash
cp deploy/rclone-ustc-bgm.env.example deploy/rclone-ustc-bgm.env
```

2. 确保生产环境里也有：

```text
BGM_DIR=/home/ubuntu/.mounts/video-maker-bgm
```

3. 安装 systemd 挂载服务

```bash
sudo cp deploy/rclone-ustc-bgm-mount.service.example /etc/systemd/system/rclone-ustc-bgm-mount.service
sudo systemctl daemon-reload
sudo systemctl enable --now rclone-ustc-bgm-mount.service
```

4. 重启 Video Maker

```bash
sudo systemctl restart video-maker.service video-maker-worker.service
```

## 验证

```bash
ls /mnt/video-maker-bgm | head
python3 - <<'PY'
from app.config import BGM_DIR
print(BGM_DIR)
PY
```

然后实际生成一个带自动 BGM 的视频，确认：

- `manifest.yaml` 可读取
- 能选出 BGM
- worker 渲染时能正常打开音频文件

## 切换完成后释放本地磁盘

确认服务稳定后，再删除本地副本：

```bash
rm -rf /home/ubuntu/personal/video-maker-server/assets/bgm
```

如果以后想回滚，把云盘内容重新同步回来，或把 `BGM_DIR` 去掉即可。
