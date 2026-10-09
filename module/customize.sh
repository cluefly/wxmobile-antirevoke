#!/system/bin/sh
ui_print "- DSport 0.1"
ui_print "- 主界面显示 step_counter、step_detector、pedometer_minute"
ui_print "- 偏移加到计步器和分钟计步的累计值上"
if [ -f "$MODPATH/dsport.apk" ]; then
  pm install -r -g "$MODPATH/dsport.apk"
  if [ "$?" -eq 0 ]; then
    ui_print "- 应用已安装。请在 Vector 中启用，作用域包含 com.oplus.exsystemservice"
  else
    ui_print "! 安装应用失败，开机后会再试"
  fi
else
  ui_print "! 缺少 dsport.apk"
fi
set_perm "$MODPATH/boot-completed.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
