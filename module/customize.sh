#!/system/bin/sh
ui_print "- 微信防撤回 0.3"
ui_print "- 别人撤回时，本设备已收到的消息保持原样，并显示红字【已撤回】"
if [ -f "$MODPATH/AntiRevoke.apk" ]; then
  pm install -r -g "$MODPATH/AntiRevoke.apk"
  if [ "$?" -eq 0 ]; then
    ui_print "- 应用已安装。请在 Xposed/Vector 中启用，作用域勾选微信 (com.tencent.mm)"
  else
    ui_print "! 安装应用失败，开机后会再试"
  fi
else
  ui_print "! 缺少 AntiRevoke.apk"
fi
set_perm "$MODPATH/boot-completed.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
