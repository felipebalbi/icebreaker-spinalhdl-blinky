PACKAGE  := i2c
TOP      := I2cControllerDemo
SIM_MAIN := I2cControllerSim
SRC_DIR  := src
GEN_DIR  := gen
SIM_DIR  := simWorkspace
PCF      := icebreaker.pcf

# IP revision -- single source of truth for the REVISION register
# at offset 0x00 of I2cController. Threaded into every sbt invocation
# that elaborates the controller (REVISION_DEFS below) and read on the
# Scala side via sys.props in `src/hw/Revision.scala` (lands with
# Step 6, copied from the Uart project). Keep the defaults there in
# lockstep with these values.
REVISION_MAJOR := 0
REVISION_MINOR := 1
REVISION_PATCH := 0
REVISION_DEFS  := -Drevision.major=$(REVISION_MAJOR) \
                  -Drevision.minor=$(REVISION_MINOR) \
                  -Drevision.patch=$(REVISION_PATCH)

# Tools
SBT     := sbt $(REVISION_DEFS)
YOSYS   := yosys
NEXTPNR := nextpnr-ice40
ICEPACK := icepack
ICEPROG := iceprog
GTKWAVE := gtkwave

# Derived files
V    := $(GEN_DIR)/$(TOP).v
JSON := $(GEN_DIR)/$(TOP).json
ASC  := $(GEN_DIR)/$(TOP).asc
BIN  := $(GEN_DIR)/$(TOP).bin

# Hardware sources only -- sim sources don't affect Verilog generation,
# so excluding them prevents needless rebuilds when iterating on tests.
#
# The cross-project Uart sources are pulled in too (this project depends
# on `uart` via ProjectRef in build.sbt; see I2c/AGENTS.md "Cross-project
# deps") so an upstream UartController tweak invalidates gen/<TOP>.v.
HW_SRCS := $(shell find $(SRC_DIR)/hw -name '*.scala') \
           $(shell find ../Uart/src/hw -name '*.scala')

# Default target
.DEFAULT_GOAL := all

all: $(BIN) ## Build flashable bitstream (gen/<TOP>.bin)

$(GEN_DIR):
	mkdir -p $(GEN_DIR)

# 1. Generate Verilog (Spinal -> Verilog)
$(V): $(HW_SRCS) | $(GEN_DIR)
	$(SBT) -batch "runMain $(PACKAGE).$(TOP)Verilog"

# 2. Synthesize (Verilog -> JSON)
$(JSON): $(V)
	$(YOSYS) -p "synth_ice40 -top $(TOP) -json $@" $<

# 3. Place & route (JSON -> ASC)
$(ASC): $(JSON) $(PCF)
	$(NEXTPNR) \
		--up5k          \
		--package sg48  \
		--pcf $(PCF)    \
		--json $< \
		--asc $@  \
		--freq 12

# 4. Pack (ASC -> BIN)
$(BIN): $(ASC)
	$(ICEPACK) $< $@

# Aggregate sim target -- per-block sim targets get added as the
# project grows. Keep this list and .PHONY in sync.
sim: sim-i2cio sim-bustiming sim-bitctrl sim-bytectrl ## Run all simulations

sim-i2cio: ## Run I2cIo (wired-AND bus model) simulation
	$(SBT) -batch "runMain $(PACKAGE).I2cIoSim"

sim-bustiming: ## Run BusTiming (pure-Scala spec-floor audit) simulation
	$(SBT) -batch "runMain $(PACKAGE).BusTimingSim"

sim-bitctrl: ## Run I2cBitController (bit-level FSM) simulation
	$(SBT) -batch "runMain $(PACKAGE).I2cBitControllerSim"

sim-bytectrl: ## Run I2cByteController (byte-level FSM) simulation
	$(SBT) -batch "runMain $(PACKAGE).I2cByteControllerSim"

# The next three targets fail until Step 6 (I2cController) ships --
# the Scala entrypoints (I2cControllerSim, I2cControllerVerilog,
# I2cControllerDocs) don't exist yet. They're declared here so the
# build contract for Step 6 is locked in: that's what the controller
# step has to deliver, and `make help` advertises them in advance.

sim-controller: ## Run I2cController APB-driven simulation (lands with Step 6)
	$(SBT) -batch "runMain $(PACKAGE).I2cControllerSim"

gen-controller: | $(GEN_DIR) ## Generate I2cController.v in gen/ (lands with Step 6)
	$(SBT) -batch "runMain $(PACKAGE).I2cControllerVerilog"

gen-demo: | $(GEN_DIR) ## Generate I2cControllerDemo.v in gen/ (Step 7 demo top)
	$(SBT) -batch "runMain $(PACKAGE).I2cControllerDemoVerilog"

docs: | $(GEN_DIR) ## Generate IP datasheet (HTML / C / JSON / RALF / RDL) in gen/ (lands with Step 6)
	$(SBT) -batch "runMain $(PACKAGE).I2cControllerDocs"

waves: ## Open the most recent simulation VCD in gtkwave
	@vcd=$$(find $(SIM_DIR) -name '*.vcd' -printf '%T@ %p\n' 2>/dev/null \
		| sort -nr | head -1 | cut -d' ' -f2-); \
	if [ -z "$$vcd" ]; then \
		echo "no .vcd found under $(SIM_DIR)/ -- run a sim first"; \
		exit 1; \
	fi; \
	echo "opening $$vcd"; \
	$(GTKWAVE) "$$vcd" &

flash: $(BIN) ## Flash bitstream to the iCEbreaker
	$(ICEPROG) $<

clean: ## Remove generated artefacts (gen/, simWorkspace/)
	rm -rf $(GEN_DIR) $(SIM_DIR)

distclean: clean ## clean + nuke sbt build state (target/, project/target/)
	rm -rf target project/target project/project

help: ## Show this help (every target with a ## description)
	@awk 'BEGIN { FS = ":.*## "; printf "Available targets:\n\n" } \
		/^[a-zA-Z0-9_.-]+:.*## / { printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2 }' \
		$(MAKEFILE_LIST)

.PHONY: all sim sim-i2cio sim-bustiming sim-bitctrl sim-bytectrl sim-controller gen-controller gen-demo docs waves flash clean distclean help
