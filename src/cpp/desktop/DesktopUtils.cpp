/*
 * DesktopUtils.cpp
 *
 * Copyright (C) 2009-18 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */

#include "DesktopUtils.hpp"

#include <QPushButton>
#include <QTimer>
#include <QDesktopServices>

#include <core/FileSerializer.hpp>
#include <core/system/Environment.hpp>

#include "DesktopOptions.hpp"
#include "DesktopMainWindow.hpp"

#ifdef Q_OS_WIN
#include <windows.h>
#endif

using namespace rstudio::core;

namespace rstudio {
namespace desktop {

#ifdef Q_OS_WIN

void reattachConsoleIfNecessary()
{
   if (::AttachConsole(ATTACH_PARENT_PROCESS))
   {
      freopen("CONOUT$","wb",stdout);
      freopen("CONOUT$","wb",stderr);
      freopen("CONIN$","rb",stdin);
      std::ios::sync_with_stdio();
   }
}

#else

void reattachConsoleIfNecessary()
{

}

#endif

// NOTE: this code is duplicated in diagnostics as well (and also in
// SessionOptions.hpp although the code path isn't exactly the same)
FilePath userLogPath()
{
   FilePath userHomePath = core::system::userHomePath("R_USER|HOME");
   FilePath logPath = core::system::userSettingsPath(
         userHomePath,
         "RStudio-Desktop").childPath("log");
   return logPath;
}

bool isWindows()
{
#ifdef Q_OS_WIN
   return true;
#else
   return false;
#endif
}

#ifndef Q_OS_MAC
double devicePixelRatio(QMainWindow* pMainWindow)
{
   return 1.0;
}

bool isOSXMavericks()
{
   return false;
}

// NOTE: also RHEL
bool isCentOS()
{
   FilePath redhatRelease("/etc/redhat-release");
   if (!redhatRelease.exists())
      return false;

   std::string contents;
   Error error = readStringFromFile(redhatRelease, &contents);
   if (error)
      return false;

   return contents.find("CentOS") != std::string::npos ||
          contents.find("Red Hat Enterprise Linux") != std::string::npos;
}

#endif

bool isGnomeDesktop()
{
   return core::system::getenv("DESKTOP_SESSION") == "gnome";
}

void applyDesktopTheme(QWidget* window, bool isDark)
{
#ifndef Q_OS_MAC
   std::string lightSheetName = isWindows()
         ? "rstudio-windows-light.qss"
         : "rstudio-gnome-light.qss";

   std::string darkSheetName = isWindows()
         ? "rstudio-windows-dark.qss"
         : "rstudio-gnome-dark.qss";

   FilePath stylePath = isDark
         ? options().resourcesPath().complete("stylesheets").complete(darkSheetName)
         : options().resourcesPath().complete("stylesheets").complete(lightSheetName);

   std::string stylesheet;
   Error error = core::readStringFromFile(stylePath, &stylesheet);
   if (error)
      LOG_ERROR(error);

   window->setStyleSheet(QString::fromStdString(stylesheet));
#endif
}

#ifndef Q_OS_MAC

void enableFullscreenMode(QMainWindow* pMainWindow, bool primary)
{

}

void toggleFullscreenMode(QMainWindow* pMainWindow)
{

}

bool supportsFullscreenMode(QMainWindow* pMainWindow)
{
   return false;
}

void initializeLang()
{
}

void finalPlatformInitialize(MainWindow* pMainWindow)
{

}

#endif

void raiseAndActivateWindow(QWidget* pWindow)
{
   // WId wid = pWindow->effectiveWinId(); -- gets X11 window id
   // gtk_window_present_with_time(GTK_WINDOW, timestamp)

   if (pWindow->isMinimized())
   {
      pWindow->setWindowState(
                     pWindow->windowState() & ~Qt::WindowMinimized);
   }

   pWindow->raise();
   pWindow->activateWindow();
}

void moveWindowBeneath(QWidget* pTop, QWidget* pBottom)
{
#ifdef WIN32
   HWND hwndTop = reinterpret_cast<HWND>(pTop->winId());
   HWND hwndBottom = reinterpret_cast<HWND>(pBottom->winId());
   ::SetWindowPos(hwndBottom, hwndTop, 0, 0, 0, 0,
                  SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
#endif
   // not currently supported on Linux--Qt doesn't provide a way to view or
   // change the window stacking order
}

void closeWindow(QWidget* pWindow)
{
   pWindow->close();
}

QMessageBox::Icon safeMessageBoxIcon(QMessageBox::Icon icon)
{
   // if a gtk theme has a missing or corrupt icon for one of the stock
   // dialog images, qt crashes when attempting to show the dialog
#ifdef Q_OS_LINUX
   return QMessageBox::NoIcon;
#else
   return icon;
#endif
}

bool showYesNoDialog(QMessageBox::Icon icon,
                     QWidget *parent,
                     const QString &title,
                     const QString& text,
                     const QString& informativeText,
                     bool yesDefault)
{
   // basic message box attributes
   QMessageBox messageBox(parent);
   messageBox.setIcon(safeMessageBoxIcon(icon));
   messageBox.setWindowTitle(title);
   messageBox.setText(text);
   if (informativeText.length() > 0)
      messageBox.setInformativeText(informativeText);
   messageBox.setWindowModality(Qt::WindowModal);
   messageBox.setWindowFlag(Qt::WindowContextHelpButtonHint, false);

   // initialize buttons
   QPushButton* pYes = messageBox.addButton(QMessageBox::Yes);
   QPushButton* pNo = messageBox.addButton(QMessageBox::No);
   if (yesDefault)
      messageBox.setDefaultButton(pYes);
   else
      messageBox.setDefaultButton(pNo);

   // show the dialog modally
   messageBox.exec();

   // return true if the user clicked yes
   return messageBox.clickedButton() == pYes;
}

void showMessageBox(QMessageBox::Icon icon,
                    QWidget *parent,
                    const QString &title,
                    const QString& text,
                    const QString& informativeText)
{
   QMessageBox messageBox(parent);
   messageBox.setIcon(safeMessageBoxIcon(icon));
   messageBox.setWindowTitle(title);
   messageBox.setText(text);
   if (informativeText.length() > 0)
      messageBox.setInformativeText(informativeText);
   messageBox.setWindowModality(Qt::WindowModal);
   messageBox.setWindowFlag(Qt::WindowContextHelpButtonHint, false);
   messageBox.addButton(new QPushButton(QString::fromUtf8("OK")), QMessageBox::AcceptRole);
   messageBox.exec();
}

void showError(QWidget *parent,
               const QString &title,
               const QString& text,
               const QString& informativeText)
{
   showMessageBox(QMessageBox::Critical, parent, title, text, informativeText);
}

void showWarning(QWidget *parent,
                 const QString &title,
                 const QString& text,
                 const QString& informativeText)
{
   showMessageBox(QMessageBox::Warning, parent, title, text, informativeText);
}

void showInfo(QWidget* parent,
              const QString& title,
              const QString& text,
              const QString& informativeText)
{
   showMessageBox(QMessageBox::Information, parent, title, text, informativeText);
}

void showFileError(const QString& action,
                   const QString& file,
                   const QString& error)
{
   QString msg = QString::fromUtf8("Error ") + action +
                 QString::fromUtf8(" ") + file +
                 QString::fromUtf8(" - ") + error;
   showMessageBox(QMessageBox::Critical,
                  nullptr,
                  QString::fromUtf8("File Error"),
                  msg,
                  QString());
}

bool isFixedWidthFont(const QFont& font)
{
   QFontMetrics metrics(font);
   int width = metrics.width(QChar::fromLatin1(' '));
   char chars[] = {'m', 'i', 'A', '/', '-', '1', 'l', '!', 'x', 'X', 'y', 'Y'};
   for (char i : chars)
   {
      if (metrics.width(QChar::fromLatin1(i)) != width)
         return false;
   }
   return true;
}

int getDpi()
{
   // TODO: we may need to tweak this to ensure that the DPI
   // discovered respects the screen a particular instance
   // that RStudio lives on (e.g. for users with multiple
   // displays with different DPIs)
   return (int) qApp->primaryScreen()->logicalDotsPerInch();
}

double getDpiZoomScaling()
{
   // TODO: because Qt is already high-DPI aware and automatically
   // scales in most scenarios, we no longer need to detect and
   // apply a custom scale -- but more testing is warranted
   return 1.0;
}

#ifdef _WIN32

// on Win32 open urls using our special urlopener.exe -- this is
// so that the shell exec is made out from under our windows "job"
void openUrl(const QUrl& url)
{
   // we allow default handling for  mailto and file schemes because qt
   // does custom handling for them and they aren't affected by the chrome
   //job object issue noted above
   if (url.scheme() == QString::fromUtf8("mailto") ||
       url.scheme() == QString::fromUtf8("file"))
   {
      QDesktopServices::openUrl(url);
   }
   else
   {
      core::system::ProcessOptions options;
      options.breakawayFromJob = true;
      options.detachProcess = true;

      std::vector<std::string> args;
      args.push_back(url.toString().toStdString());

      core::system::ProcessResult result;
      Error error = core::system::runProgram(
            desktop::options().urlopenerPath().absolutePath(),
            args,
            "",
            options,
            &result);

      if (error)
         LOG_ERROR(error);
      else if (result.exitStatus != EXIT_SUCCESS)
         LOG_ERROR_MESSAGE(result.stdErr);
   }
}

// Qt 4.8.3 on Win7 (32-bit) has problems with opening the ~ directory
// (it attempts to navigate to the "Documents library" and then hangs)
// So we use the Qt file dialog implementations when we are running
// on Win32
QFileDialog::Options standardFileDialogOptions()
{
   return 0;
}

#else

void openUrl(const QUrl& url)
{
   QDesktopServices::openUrl(url);
}

QFileDialog::Options standardFileDialogOptions()
{
   return nullptr;
}

#endif

} // namespace desktop
} // namespace rstudio
