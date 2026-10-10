/*
 * termdeb-mir-input-stub - a minimal Mir input platform for the TermDeb desktop.
 *
 * Why this exists
 * ---------------
 * Mir refuses to start without an input platform:
 *
 *   ERROR: input_probe.cpp(183): No appropriate input platform module found
 *
 * The only input platform Debian trixie ships is mir-platform-input-evdev10, and
 * it cannot work in the TermDeb guest:
 *
 *   * evdev needs udev (it dies with "Failed to create udev_monitor", and
 *     systemd/udevd is not PID 1 under proot);
 *   * Android does not expose /dev/input to the app.
 *
 * That is fine, because the TermDeb display bridge feeds *all* input back to Mir
 * over Wayland (zwp_virtual_keyboard_manager_v1 and
 * zwlr_virtual_pointer_manager_v1), so the guest never needs a native input
 * platform. This plugin provides exactly that: a platform that owns no devices
 * and does nothing, so Mir's probe finds something and starts.
 *
 * It is compiled at build time by termdeb-assets/prebuilt/provision-desktop.sh
 * and installed into the guest's Mir server-platform directory.
 *
 * ABI version symbol
 * ------------------
 * Mir resolves the entry points with dlvsym(), i.e. it looks up e.g.
 * "describe_input_module@MIR_INPUT_PLATFORM_0.27" (see
 * src/common/sharedlibrary/shared_library.cpp in Mir and
 * src/server/input/input_probe.cpp, which passes
 * MIR_SERVER_INPUT_PLATFORM_VERSION as the version). The version string is not
 * exposed by any installed header, so provision-desktop.sh reads it out of
 * libmirserver and generates the linker version script that stamps it onto the
 * symbols below. Without it the module loads but is never detected:
 *
 *   Failed to find the requested platform module.
 *   Detected modules are:
 *
 * See termdeb-assets/prebuilt/mir-input-stub/version-script.map.in.
 *
 * Copyright (c) TermDeb Contributors
 * SPDX-License-Identifier: MIT
 */

#include "mir/console_services.h"
#include "mir/dispatch/dispatchable.h"
#include "mir/dispatch/readable_fd.h"
#include "mir/fd.h"
#include "mir/input/input_device_registry.h"
#include "mir/input/input_report.h"
#include "mir/input/platform.h"
#include "mir/module_deleter.h"
#include "mir/module_properties.h"
#include "mir/options/option.h"
#include "mir/version.h"

#include <boost/program_options/options_description.hpp>

#include <memory>
#include <unistd.h>

namespace
{

/**
 * A dispatchable that never reports anything.
 *
 * Mir registers every input platform's dispatchable with the main dispatch loop,
 * so returning a null pointer is not an option. A pipe with no writer is used as
 * an fd that stays quiet forever; the read end is watched and the no-op callback
 * is never called.
 */
class QuietDispatchable : public mir::dispatch::Dispatchable
{
public:
    QuietDispatchable()
    {
        int fds[2];
        if (::pipe(fds) != 0)
            return;
        read_fd = mir::Fd{mir::IntOwnedFd{fds[0]}};
        write_fd = mir::Fd{mir::IntOwnedFd{fds[1]}};
    }

    mir::Fd watch_fd() const override
    {
        return read_fd;
    }

    bool dispatch(mir::dispatch::FdEvents) override
    {
        return true;
    }

    mir::dispatch::FdEvents relevant_events() const override
    {
        return mir::dispatch::FdEvent::readable;
    }

private:
    mir::Fd read_fd;
    mir::Fd write_fd;
};

class StubInputPlatform : public mir::input::Platform
{
public:
    StubInputPlatform()
        : dispatcher{std::make_shared<QuietDispatchable>()}
    {
    }

    std::shared_ptr<mir::dispatch::Dispatchable> dispatchable() override
    {
        return dispatcher;
    }

    void start() override {}
    void stop() override {}
    void pause_for_config() override {}
    void continue_after_config() override {}

private:
    std::shared_ptr<mir::dispatch::Dispatchable> dispatcher;
};

}  // namespace

extern "C"
{

mir::UniqueModulePtr<mir::input::Platform> create_input_platform(
    mir::options::Option const&,
    std::shared_ptr<mir::EmergencyCleanupRegistry> const&,
    std::shared_ptr<mir::input::InputDeviceRegistry> const&,
    std::shared_ptr<mir::ConsoleServices> const&,
    std::shared_ptr<mir::input::InputReport> const&)
{
    return mir::make_module_ptr<StubInputPlatform>();
}

void add_input_platform_options(boost::program_options::options_description&)
{
}

mir::input::PlatformPriority probe_input_platform(
    mir::options::Option const&,
    mir::ConsoleServices&)
{
    // "dummy" rather than "unsupported": this platform is usable, it just has no
    // devices. Anything higher would claim the best platform and is unnecessary.
    return mir::input::PlatformPriority::dummy;
}

mir::ModuleProperties const* describe_input_module()
{
    static mir::ModuleProperties const module_properties = {
        "termdeb:input-stub",
        MIR_SERVER_MAJOR_VERSION,
        MIR_SERVER_MINOR_VERSION,
        MIR_SERVER_MICRO_VERSION,
        "input-termdeb-stub.so"};
    return &module_properties;
}

}  // extern "C"
